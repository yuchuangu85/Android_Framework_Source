/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.system;

/**
 * To see why this class exists read the comment near {@link OsConstants#AF_INET}
 * (the first field of that class).
 *
 * @hide
 */
final class OsConstantsHolder {

    static int AI_ADDRCONFIG;
    static int AI_ALL;
    static int AI_CANONNAME;
    static int AI_NUMERICHOST;
    static int AI_NUMERICSERV;
    static int AI_PASSIVE;
    static int AI_V4MAPPED;

    static int EAI_AGAIN;
    static int EAI_BADFLAGS;
    static int EAI_FAIL;
    static int EAI_FAMILY;
    static int EAI_MEMORY;
    static int EAI_NODATA;
    static int EAI_NONAME;
    static int EAI_OVERFLOW;
    static int EAI_SERVICE;
    static int EAI_SOCKTYPE;
    static int EAI_SYSTEM;

    static int F_GETLK;
    static int F_GETLK64;
    static int F_SETLK;
    static int F_SETLK64;
    static int F_SETLKW;
    static int F_SETLKW64;

    static int NI_NAMEREQD;
    static int NI_NOFQDN;
    static int NI_NUMERICHOST;
    static int NI_NUMERICSERV;

    static int O_DIRECT;
    static int O_NOFOLLOW;

    static int SIGRTMIN;

    static int _SC_2_CHAR_TERM;
    static int _SC_2_C_BIND;
    static int _SC_2_C_DEV;
    static int _SC_2_C_VERSION;
    static int _SC_2_FORT_DEV;
    static int _SC_2_FORT_RUN;
    static int _SC_2_LOCALEDEF;
    static int _SC_2_SW_DEV;
    static int _SC_2_UPE;
    static int _SC_2_VERSION;
    static int _SC_AIO_LISTIO_MAX;
    static int _SC_AIO_MAX;
    static int _SC_AIO_PRIO_DELTA_MAX;
    static int _SC_ARG_MAX;
    static int _SC_ASYNCHRONOUS_IO;
    static int _SC_ATEXIT_MAX;
    static int _SC_AVPHYS_PAGES;
    static int _SC_BC_BASE_MAX;
    static int _SC_BC_DIM_MAX;
    static int _SC_BC_SCALE_MAX;
    static int _SC_BC_STRING_MAX;
    static int _SC_CHILD_MAX;
    static int _SC_CLK_TCK;
    static int _SC_COLL_WEIGHTS_MAX;
    static int _SC_DELAYTIMER_MAX;
    static int _SC_EXPR_NEST_MAX;
    static int _SC_FSYNC;
    static int _SC_GETGR_R_SIZE_MAX;
    static int _SC_GETPW_R_SIZE_MAX;
    static int _SC_IOV_MAX;
    static int _SC_JOB_CONTROL;
    static int _SC_LINE_MAX;
    static int _SC_LOGIN_NAME_MAX;
    static int _SC_MAPPED_FILES;
    static int _SC_MEMLOCK;
    static int _SC_MEMLOCK_RANGE;
    static int _SC_MEMORY_PROTECTION;
    static int _SC_MESSAGE_PASSING;
    static int _SC_MQ_OPEN_MAX;
    static int _SC_MQ_PRIO_MAX;
    static int _SC_NGROUPS_MAX;
    static int _SC_NPROCESSORS_CONF;
    static int _SC_NPROCESSORS_ONLN;
    static int _SC_OPEN_MAX;
    static int _SC_PAGESIZE;
    static int _SC_PAGE_SIZE;
    static int _SC_PASS_MAX;
    static int _SC_PHYS_PAGES;
    static int _SC_PRIORITIZED_IO;
    static int _SC_PRIORITY_SCHEDULING;
    static int _SC_REALTIME_SIGNALS;
    static int _SC_RE_DUP_MAX;
    static int _SC_RTSIG_MAX;
    static int _SC_SAVED_IDS;
    static int _SC_SEMAPHORES;
    static int _SC_SEM_NSEMS_MAX;
    static int _SC_SEM_VALUE_MAX;
    static int _SC_SHARED_MEMORY_OBJECTS;
    static int _SC_SIGQUEUE_MAX;
    static int _SC_STREAM_MAX;
    static int _SC_SYNCHRONIZED_IO;
    static int _SC_THREADS;
    static int _SC_THREAD_ATTR_STACKADDR;
    static int _SC_THREAD_ATTR_STACKSIZE;
    static int _SC_THREAD_DESTRUCTOR_ITERATIONS;
    static int _SC_THREAD_KEYS_MAX;
    static int _SC_THREAD_PRIORITY_SCHEDULING;
    static int _SC_THREAD_PRIO_INHERIT;
    static int _SC_THREAD_PRIO_PROTECT;
    static int _SC_THREAD_SAFE_FUNCTIONS;
    static int _SC_THREAD_STACK_MIN;
    static int _SC_THREAD_THREADS_MAX;
    static int _SC_TIMERS;
    static int _SC_TIMER_MAX;
    static int _SC_TTY_NAME_MAX;
    static int _SC_TZNAME_MAX;
    static int _SC_VERSION;
    static int _SC_XBS5_ILP32_OFF32;
    static int _SC_XBS5_ILP32_OFFBIG;
    static int _SC_XBS5_LP64_OFF64;
    static int _SC_XBS5_LPBIG_OFFBIG;
    static int _SC_XOPEN_CRYPT;
    static int _SC_XOPEN_ENH_I18N;
    static int _SC_XOPEN_LEGACY;
    static int _SC_XOPEN_REALTIME;
    static int _SC_XOPEN_REALTIME_THREADS;
    static int _SC_XOPEN_SHM;
    static int _SC_XOPEN_UNIX;
    static int _SC_XOPEN_VERSION;
    static int _SC_XOPEN_XCU_VERSION;

    private static native void initConstants();

    static {
        initConstants();
    }
}
