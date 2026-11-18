//! Gamepad Controller & Right-Stick Camera Orbit Engine for NFSU2 Android.
//!
//! Provides comprehensive controller axis evaluation, right analog stick disambiguation,
//! deadzone filtering, smooth camera orbit dynamics, and synchronization with the
//! game engine's camera views.

use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::Mutex;
use std::time::Instant;

const DEADZONE_THRESHOLD: f32 = 0.15;
const SATURATION_THRESHOLD: f32 = 0.85;
const PI_F: f32 = std::f32::consts::PI;
const TAU_SMOOTH: f32 = 0.09;
const SNAP_REAR_ANGLE: f32 = 0.44; // within ~25 degrees of straight back
const SNAP_REAR_RADIUS: f32 = 0.50;

type RoamStickFn = unsafe extern "C" fn(f32, f32);

pub struct GamepadCameraEngine {
    stick_x: AtomicI32, // fixed-point f32 * 10000.0
    stick_y: AtomicI32,
    current_yaw: AtomicI32,
    is_snapped_rear: AtomicBool,
    last_tick: Mutex<Option<Instant>>,
    roam_stick_cb: Mutex<Option<RoamStickFn>>,
}

impl GamepadCameraEngine {
    pub const fn new() -> Self {
        Self {
            stick_x: AtomicI32::new(0),
            stick_y: AtomicI32::new(0),
            current_yaw: AtomicI32::new(0),
            is_snapped_rear: AtomicBool::new(false),
            last_tick: Mutex::new(None),
            roam_stick_cb: Mutex::new(None),
        }
    }

    #[inline]
    pub fn get_stick(&self) -> (f32, f32) {
        let x = self.stick_x.load(Ordering::Acquire) as f32 / 10000.0;
        let y = self.stick_y.load(Ordering::Acquire) as f32 / 10000.0;
        (x, y)
    }

    #[inline]
    pub fn set_stick(&self, x: f32, y: f32) {
        self.stick_x.store((x * 10000.0) as i32, Ordering::Release);
        self.stick_y.store((y * 10000.0) as i32, Ordering::Release);
    }

    #[inline]
    pub fn get_yaw(&self) -> f32 {
        self.current_yaw.load(Ordering::Acquire) as f32 / 10000.0
    }

    #[inline]
    pub fn set_yaw(&self, yaw: f32) {
        self.current_yaw.store((yaw * 10000.0) as i32, Ordering::Release);
    }

    pub fn set_roam_stick_callback(&self, cb: Option<RoamStickFn>) {
        if let Ok(mut guard) = self.roam_stick_cb.lock() {
            *guard = cb;
        }
    }

    /// Disambiguates raw gamepad axes to identify physical right analog stick coordinates.
    ///
    /// Handles standard Android mapping (AXIS_Z / AXIS_RZ as Right Stick, AXIS_BRAKE / AXIS_GAS as Triggers),
    /// alternate mappings (AXIS_RX / AXIS_RY as Right Stick), and resting trigger values (-1.0f vs 0.0f).
    pub fn disambiguate_right_stick(
        &self,
        raw_rx: f32,
        raw_ry: f32,
        raw_z: f32,
        raw_rz: f32,
        raw_lt: f32,
        raw_rt: f32,
        raw_brake: f32,
        raw_gas: f32,
    ) -> (f32, f32) {
        let mut chosen_x = 0.0f32;
        let mut chosen_y = 0.0f32;

        let has_rx_ry_activity = raw_rx.abs() > 0.05 || raw_ry.abs() > 0.05;

        if has_rx_ry_activity {
            // Dedicated RX / RY axes active
            chosen_x = raw_rx;
            chosen_y = raw_ry;
        } else {
            // Z and RZ are resting triggers ONLY if BOTH rest near -1.0f simultaneously
            let are_resting_triggers = raw_z < -0.85 && raw_rz < -0.85;

            if !are_resting_triggers {
                // Z and RZ are the right stick! Full negative/positive deflections are preserved.
                chosen_x = raw_z;
                chosen_y = raw_rz;
            } else {
                chosen_x = raw_rx;
                chosen_y = raw_ry;
            }
        }

        // Apply radial deadzone filtering and non-linear response curve
        let mag = (chosen_x * chosen_x + chosen_y * chosen_y).sqrt();
        if mag < DEADZONE_THRESHOLD {
            return (0.0, 0.0);
        }

        // Rescale magnitude from deadzone to saturation
        let norm = ((mag - DEADZONE_THRESHOLD) / (SATURATION_THRESHOLD - DEADZONE_THRESHOLD)).clamp(0.0, 1.0);
        // Blend linear with cubic response curve for smooth precision aiming & fast orbit
        let curve = 0.70 * norm + 0.30 * norm * norm * norm;
        let scale = curve / mag;

        let out_x = (chosen_x * scale).clamp(-1.0, 1.0);
        let out_y = (chosen_y * scale).clamp(-1.0, 1.0);

        (out_x, out_y)
    }

    /// Computes target camera orbit yaw from right stick coordinates.
    ///
    /// Stick coordinates:
    /// - ( 1.0,  0.0) -> Camera looks right (+pi/2)
    /// - (-1.0,  0.0) -> Camera looks left (-pi/2)
    /// - ( 0.0,  1.0) -> Camera looks straight back (rear-view snap, +/-pi)
    /// - ( 0.0, -1.0) -> Camera looks forward
    pub fn compute_target_yaw(&self, sx: f32, sy: f32, cur_yaw: f32) -> (f32, bool) {
        let mag = (sx * sx + sy * sy).sqrt();
        if mag < DEADZONE_THRESHOLD {
            return (0.0, false);
        }

        // Angle off straight rear (sx=0, sy=1 -> atan2(0, -1) = pi)
        let off_rear = PI_F - (sx.atan2(-sy)).abs();

        // Rear-view snap
        if off_rear < SNAP_REAR_ANGLE && mag > SNAP_REAR_RADIUS {
            let snap = if cur_yaw < -0.05 { -PI_F } else { PI_F };
            return (snap, true);
        }

        let k = ((mag - DEADZONE_THRESHOLD) / (SATURATION_THRESHOLD - DEADZONE_THRESHOLD)).min(1.0);
        let mut dir = sx.atan2(-sy);

        let side = if cur_yaw.abs() > 0.05 {
            if cur_yaw > 0.0 { 1.0 } else { -1.0 }
        } else if sx >= 0.0 {
            1.0
        } else {
            -1.0
        };

        if dir.abs() > 2.4 && (if dir > 0.0 { 1.0 } else { -1.0 }) != side {
            dir += side * 2.0 * PI_F;
        }

        (dir * k, false)
    }

    /// Ticks smooth camera yaw interpolation towards target yaw.
    pub fn update_yaw(&self) -> f32 {
        let now = Instant::now();
        let dt = {
            if let Ok(mut guard) = self.last_tick.lock() {
                let prev = guard.replace(now);
                match prev {
                    Some(p) => now.duration_since(p).as_secs_f32().min(0.25),
                    None => 0.016,
                }
            } else {
                0.016
            }
        };

        let (sx, sy) = self.get_stick();
        let mut cur_yaw = self.get_yaw();
        let (target_yaw, is_rear) = self.compute_target_yaw(sx, sy, cur_yaw);
        self.is_snapped_rear.store(is_rear, Ordering::Release);

        if is_rear {
            self.set_yaw(target_yaw);
            return target_yaw;
        }

        let mut diff = target_yaw - cur_yaw;
        while diff > PI_F { diff -= 2.0 * PI_F; }
        while diff < -PI_F { diff += 2.0 * PI_F; }

        let decay = 1.0 - (-dt / TAU_SMOOTH).exp();
        cur_yaw += diff * decay;

        while cur_yaw > PI_F { cur_yaw -= 2.0 * PI_F; }
        while cur_yaw < -PI_F { cur_yaw += 2.0 * PI_F; }

        if target_yaw == 0.0 && cur_yaw.abs() < 0.001 {
            cur_yaw = 0.0;
        }

        self.set_yaw(cur_yaw);
        cur_yaw
    }

    /// Dispatches processed right stick input to registered native hooks.
    pub fn dispatch(&self, rx: f32, ry: f32) {
        self.set_stick(rx, ry);

        // 1. Dispatch to roam hook (look_process_view in roam_android.c)
        if let Ok(guard) = self.roam_stick_cb.lock() {
            if let Some(cb) = *guard {
                unsafe { cb(rx, ry); }
                return;
            }
        }

        // Fallback: dynamic symbol resolution via RTLD_DEFAULT
        unsafe {
            static mut RESOLVED_FN: Option<RoamStickFn> = None;
            static mut TRIED_RESOLVE: bool = false;

            if !TRIED_RESOLVE {
                let sym_names: [&[u8]; 3] = [
                    b"roam_stick_input\0",
                    b"nfs_roam_stick_input\0",
                    b"Java_io_mininxd_nfsu2_AdhocRoamClient_nativeRoamStick\0",
                ];
                for name in sym_names {
                    let ptr = libc::dlsym(libc::RTLD_DEFAULT, name.as_ptr() as *const _);
                    if !ptr.is_null() {
                        RESOLVED_FN = Some(std::mem::transmute(ptr));
                        break;
                    }
                }
                TRIED_RESOLVE = true;
            }

            if let Some(f) = RESOLVED_FN {
                f(rx, ry);
            }
        }
    }
}

pub static GLOBAL_CONTROLLER_CAMERA: GamepadCameraEngine = GamepadCameraEngine::new();

// ---------------------------------------------------------------------------
// C-ABI Exports for Gamepad Controller Integration
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn nfs_controller_camera_process_axes(
    raw_rx: f32,
    raw_ry: f32,
    raw_z: f32,
    raw_rz: f32,
    raw_lt: f32,
    raw_rt: f32,
    raw_brake: f32,
    raw_gas: f32,
    out_x: *mut f32,
    out_y: *mut f32,
) -> i32 {
    let (x, y) = GLOBAL_CONTROLLER_CAMERA.disambiguate_right_stick(
        raw_rx, raw_ry, raw_z, raw_rz, raw_lt, raw_rt, raw_brake, raw_gas,
    );

    if !out_x.is_null() {
        unsafe { *out_x = x; }
    }
    if !out_y.is_null() {
        unsafe { *out_y = y; }
    }

    if x != 0.0 || y != 0.0 { 1 } else { 0 }
}

#[no_mangle]
pub extern "C" fn nfs_controller_camera_feed(rx: f32, ry: f32) {
    GLOBAL_CONTROLLER_CAMERA.dispatch(rx, ry);
}

#[no_mangle]
pub extern "C" fn nfs_controller_camera_register_roam_stick(cb: Option<RoamStickFn>) {
    GLOBAL_CONTROLLER_CAMERA.set_roam_stick_callback(cb);
}

#[no_mangle]
pub extern "C" fn nfs_controller_camera_get_yaw() -> f32 {
    GLOBAL_CONTROLLER_CAMERA.get_yaw()
}

#[no_mangle]
pub extern "C" fn nfs_controller_camera_update_yaw() -> f32 {
    GLOBAL_CONTROLLER_CAMERA.update_yaw()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_gamesir_z_rz_axes() {
        let engine = GamepadCameraEngine::new();

        // GameSir Nova 2 Lite: Right stick centered (Z=0, RZ=0, RX=0, RY=0)
        let (cx, cy) = engine.disambiguate_right_stick(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assert_eq!(cx, 0.0);
        assert_eq!(cy, 0.0);

        // GameSir Nova 2 Lite: Right stick tilted right (Z=0.80, RZ=0.0)
        let (rx, ry) = engine.disambiguate_right_stick(0.0, 0.0, 0.80, 0.0, 0.0, 0.0, 0.0, 0.0);
        assert!(rx > 0.5);
        assert_eq!(ry, 0.0);

        // DirectInput controller: unpressed triggers resting at -1.0
        let (tx, ty) = engine.disambiguate_right_stick(0.0, 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 0.0);
        assert_eq!(tx, 0.0);
        assert_eq!(ty, 0.0);
    }
}
