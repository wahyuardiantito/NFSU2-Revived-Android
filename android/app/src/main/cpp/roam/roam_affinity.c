#define _GNU_SOURCE
/* roam_affinity.c -- Performance core CPU affinity management for Android */
#include "roam_affinity.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <sched.h>
#include <android/log.h>

int nfs_bind_to_perf_cores(void) {
    long highest_freq = 0;
    long freqs[64] = {0};
    long caps[64] = {0};
    int total_cpus = sysconf(_SC_NPROCESSORS_CONF);
    if (total_cpus <= 0 || total_cpus > 64) total_cpus = 8;

    for (int i = 0; i < total_cpus; i++) {
        char path[128];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", i);
        FILE *fc = fopen(path, "r");
        if (fc) {
            long cap = 0;
            if (fscanf(fc, "%ld", &cap) == 1 && cap > 0) caps[i] = cap;
            fclose(fc);
        }

        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(path, "r");
        if (!f) {
            snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", i);
            f = fopen(path, "r");
        }
        if (f) {
            long freq = 0;
            if (fscanf(f, "%ld", &freq) == 1 && freq > 0) {
                freqs[i] = freq;
                if (freq > highest_freq) highest_freq = freq;
            }
            fclose(f);
        }
    }

    cpu_set_t cpuset, little_set, all_set;
    CPU_ZERO(&cpuset);
    CPU_ZERO(&little_set);
    CPU_ZERO(&all_set);
    for (int i = 0; i < total_cpus; i++) CPU_SET(i, &all_set);
    int count = 0;

    // 1. Primary: /proc/cpuinfo CPU part (separates Cortex-A76 0xd0b from Cortex-A55 0xd05 on Helio G100)
    FILE *cpuinfo = fopen("/proc/cpuinfo", "r");
    if (cpuinfo) {
        char line[256];
        int cur_cpu = -1;
        while (fgets(line, sizeof(line), cpuinfo)) {
            if (strncmp(line, "processor", 9) == 0) {
                char *colon = strchr(line, ':');
                if (colon) cur_cpu = atoi(colon + 1);
            } else if (strncmp(line, "CPU part", 8) == 0 && cur_cpu >= 0 && cur_cpu < total_cpus) {
                char *colon = strchr(line, ':');
                if (colon) {
                    unsigned long part = strtoul(colon + 1, NULL, 16);
                    int is_little = (part == 0xd03 || part == 0xd05 || part == 0xd46 || part == 0xd80);
                    if (part >= 0xd08 && !is_little) {
                        CPU_SET(cur_cpu, &cpuset);
                        count++;
                    }
                }
            }
        }
        fclose(cpuinfo);
    }

    // 2. Secondary: EAS cpu_capacity >= 700
    if (count == 0) {
        for (int i = 0; i < total_cpus; i++) {
            if (caps[i] >= 700) {
                CPU_SET(i, &cpuset);
                count++;
            }
        }
    }

    // 3. Tertiary: >= 93% of max clock (avoids 2.0 GHz A55 passing on 2.2 GHz G100)
    if (count == 0 && highest_freq > 0) {
        long threshold = (long)(highest_freq * 0.93);
        for (int i = 0; i < total_cpus; i++) {
            if (freqs[i] >= threshold) {
                CPU_SET(i, &cpuset);
                count++;
            }
        }
    }

    if (count == 0) {
        int start = total_cpus >= 4 ? total_cpus - 2 : (total_cpus > 1 ? total_cpus / 2 : 0);
        for (int i = start; i < total_cpus; i++) {
            CPU_SET(i, &cpuset);
            count++;
        }
    }

    int little_count = 0;
    for (int i = 0; i < total_cpus; i++) {
        if (!CPU_ISSET(i, &cpuset)) {
            CPU_SET(i, &little_set);
            little_count++;
        }
    }
    if (little_count == 0) little_set = cpuset;

    sched_setaffinity(getpid(), sizeof(cpu_set_t), &cpuset);
    int ret = sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);

    DIR *d = opendir("/proc/self/task");
    if (d) {
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            int tid = atoi(de->d_name);
            if (tid > 0) {
                char comm_path[128];
                char comm[64] = {0};
                snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%d/comm", tid);
                FILE *fc = fopen(comm_path, "r");
                if (fc) {
                    if (fgets(comm, sizeof(comm), fc)) {
                        comm[strcspn(comm, "\r\n")] = 0;
                    }
                    fclose(fc);
                }
                int is_critical = (strstr(comm, "NFSU2") != NULL ||
                                   strstr(comm, "MainRender") != NULL ||
                                   strstr(comm, "GuestBoot") != NULL ||
                                   strstr(comm, "RenderThread") != NULL ||
                                   strcmp(comm, "main") == 0 ||
                                   strstr(comm, "dxvk") != NULL ||
                                   strstr(comm, "mininxd") != NULL ||
                                   strstr(comm, "nfsu2") != NULL ||
                                   comm[0] == '\0');
                sched_setaffinity(tid, sizeof(cpu_set_t), is_critical ? &cpuset : &all_set);
            }
        }
        closedir(d);
    }
    __android_log_print(ANDROID_LOG_INFO, "NFSU2-PerfCores", "Bound critical threads to %d big cores (ret=%d)", count, ret);
    return ret;
}
