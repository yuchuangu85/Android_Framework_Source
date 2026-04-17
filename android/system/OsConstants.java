/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;

/**
 * Constants and helper functions for use with {@link Os}.
 */
public final class OsConstants {
    @UnsupportedAppUsage
    private OsConstants() {
    }

    /**
     * Returns the index of the element in the {@link StructCapUserData} (cap_user_data)
     * array that this capability is stored in.
     *
     * @param x capability
     * @return index of the element in the {@link StructCapUserData} array storing this capability
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static int CAP_TO_INDEX(int x) { return x >>> 5; }

    /**
     * Returns the mask for the given capability. This is relative to the capability's
     * {@link StructCapUserData} (cap_user_data) element, the index of which can be
     * retrieved with {@link CAP_TO_INDEX}.
     *
     * @param x capability
     * @return mask for given capability
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static int CAP_TO_MASK(int x) { return 1 << (x & 31); }

    /**
     * Tests whether the given mode is a block device.
     */
    public static boolean S_ISBLK(int mode) { return (mode & S_IFMT) == S_IFBLK; }

    /**
     * Tests whether the given mode is a character device.
     */
    public static boolean S_ISCHR(int mode) { return (mode & S_IFMT) == S_IFCHR; }

    /**
     * Tests whether the given mode is a directory.
     */
    public static boolean S_ISDIR(int mode) { return (mode & S_IFMT) == S_IFDIR; }

    /**
     * Tests whether the given mode is a FIFO.
     */
    public static boolean S_ISFIFO(int mode) { return (mode & S_IFMT) == S_IFIFO; }

    /**
     * Tests whether the given mode is a regular file.
     */
    public static boolean S_ISREG(int mode) { return (mode & S_IFMT) == S_IFREG; }

    /**
     * Tests whether the given mode is a symbolic link.
     */
    public static boolean S_ISLNK(int mode) { return (mode & S_IFMT) == S_IFLNK; }

    /**
     * Tests whether the given mode is a socket.
     */
    public static boolean S_ISSOCK(int mode) { return (mode & S_IFMT) == S_IFSOCK; }

    /**
     * Extracts the exit status of a child. Only valid if WIFEXITED returns true.
     */
    public static int WEXITSTATUS(int status) { return (status & 0xff00) >> 8; }

    /**
     * Tests whether the child dumped core. Only valid if WIFSIGNALED returns true.
     */
    public static boolean WCOREDUMP(int status) { return (status & 0x80) != 0; }

    /**
     * Returns the signal that caused the child to exit. Only valid if WIFSIGNALED returns true.
     */
    public static int WTERMSIG(int status) { return status & 0x7f; }

    /**
     * Returns the signal that cause the child to stop. Only valid if WIFSTOPPED returns true.
     */
    public static int WSTOPSIG(int status) { return WEXITSTATUS(status); }

    /**
     * Tests whether the child exited normally.
     */
    public static boolean WIFEXITED(int status) { return (WTERMSIG(status) == 0); }

    /**
     * Tests whether the child was stopped (not terminated) by a signal.
     */
    public static boolean WIFSTOPPED(int status) { return (WTERMSIG(status) == 0x7f); }

    /**
     * Tests whether the child was terminated by a signal.
     */
    public static boolean WIFSIGNALED(int status) { return (WTERMSIG(status + 1) >= 2); }

    /*
     * Public fields of this class are defined in native and are part of ABI. However, in certain
     * cases, bionic and glibc disagree so it is not always possible to set field to an exact value
     * and it has to be obtained using JNI.
     *
     * Creating a native method per each field is not viable: there are more than 500 fields. But
     * static final fields have to be initialized in java code. Previously they were set to 0 and
     * overwritten using JNI's SetStaticIntField method. That, however, is an undefined
     * behaviour [1].
     *
     * And hence this inelegant workaround.
     *
     * [1] https://openjdk.org/jeps/8349536#Mutating-final-fields-from-native-code
     */
    public static final int AF_INET;
    public static final int AF_INET6;
    public static final int AF_NETLINK;
    public static final int AF_PACKET;
    public static final int AF_UNIX;

    /**
     * The virt-vsock address family, linux specific.
     * It is used with {@code struct sockaddr_vm} from uapi/linux/vm_sockets.h.
     *
     * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
     * @see VmSocketAddress
     */
    public static final int AF_VSOCK;
    public static final int AF_UNSPEC;
    public static final int AI_ADDRCONFIG = OsConstantsHolder.AI_ADDRCONFIG;
    public static final int AI_ALL = OsConstantsHolder.AI_ALL;
    public static final int AI_CANONNAME = OsConstantsHolder.AI_CANONNAME;
    public static final int AI_NUMERICHOST = OsConstantsHolder.AI_NUMERICHOST;
    public static final int AI_NUMERICSERV = OsConstantsHolder.AI_NUMERICSERV;
    public static final int AI_PASSIVE = OsConstantsHolder.AI_PASSIVE;
    public static final int AI_V4MAPPED = OsConstantsHolder.AI_V4MAPPED;
    public static final int ARPHRD_ETHER;

    /**
     * Gets the protection mask for an ashmem region.
     */
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public static final int ASHMEM_GET_PROT_MASK;

    /**
      * The virtio-vsock {@code svmPort} value to bind for any available port.
      *
      * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
      * @see VmSocketAddress
      */
    public static final int VMADDR_PORT_ANY;

    /**
      * The virtio-vsock {@code svmCid} value to listens for all CIDs.
      *
      * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
      * @see VmSocketAddress
      */
    public static final int VMADDR_CID_ANY;

    /**
      * The virtio-vsock {@code svmCid} value for host communication.
      *
      * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
      * @see VmSocketAddress
      */
    public static final int VMADDR_CID_LOCAL;

    /**
      * The virtio-vsock {@code svmCid} value for loopback communication.
      *
      * @see <a href="https://man7.org/linux/man-pages/man7/vsock.7.html">vsock(7)</a>
      * @see VmSocketAddress
      */
    public static final int VMADDR_CID_HOST;

    /**
     * ARP protocol loopback device identifier.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int ARPHRD_LOOPBACK;
    public static final int CAP_AUDIT_CONTROL;
    public static final int CAP_AUDIT_WRITE;
    public static final int CAP_BLOCK_SUSPEND;
    public static final int CAP_CHOWN;
    public static final int CAP_DAC_OVERRIDE;
    public static final int CAP_DAC_READ_SEARCH;
    public static final int CAP_FOWNER;
    public static final int CAP_FSETID;
    public static final int CAP_IPC_LOCK;
    public static final int CAP_IPC_OWNER;
    public static final int CAP_KILL;
    public static final int CAP_LAST_CAP;
    public static final int CAP_LEASE;
    public static final int CAP_LINUX_IMMUTABLE;
    public static final int CAP_MAC_ADMIN;
    public static final int CAP_MAC_OVERRIDE;
    public static final int CAP_MKNOD;
    public static final int CAP_NET_ADMIN;
    public static final int CAP_NET_BIND_SERVICE;
    public static final int CAP_NET_BROADCAST;
    public static final int CAP_NET_RAW;
    public static final int CAP_SETFCAP;
    public static final int CAP_SETGID;
    public static final int CAP_SETPCAP;
    public static final int CAP_SETUID;
    public static final int CAP_SYS_ADMIN;
    public static final int CAP_SYS_BOOT;
    public static final int CAP_SYS_CHROOT;
    public static final int CAP_SYSLOG;
    public static final int CAP_SYS_MODULE;
    public static final int CAP_SYS_NICE;
    public static final int CAP_SYS_PACCT;
    public static final int CAP_SYS_PTRACE;
    public static final int CAP_SYS_RAWIO;
    public static final int CAP_SYS_RESOURCE;
    public static final int CAP_SYS_TIME;
    public static final int CAP_SYS_TTY_CONFIG;
    public static final int CAP_WAKE_ALARM;
    public static final int EAI_AGAIN = OsConstantsHolder.EAI_AGAIN;
    public static final int EAI_BADFLAGS = OsConstantsHolder.EAI_BADFLAGS;
    public static final int EAI_FAIL = OsConstantsHolder.EAI_FAIL;
    public static final int EAI_FAMILY = OsConstantsHolder.EAI_FAMILY;
    public static final int EAI_MEMORY = OsConstantsHolder.EAI_MEMORY;
    public static final int EAI_NODATA = OsConstantsHolder.EAI_NODATA;
    public static final int EAI_NONAME = OsConstantsHolder.EAI_NONAME;
    public static final int EAI_OVERFLOW = OsConstantsHolder.EAI_OVERFLOW;
    public static final int EAI_SERVICE = OsConstantsHolder.EAI_SERVICE;
    public static final int EAI_SOCKTYPE = OsConstantsHolder.EAI_SOCKTYPE;
    public static final int EAI_SYSTEM = OsConstantsHolder.EAI_SYSTEM;
    public static final int E2BIG;
    public static final int EACCES;
    public static final int EADDRINUSE;
    public static final int EADDRNOTAVAIL;
    public static final int EAFNOSUPPORT;
    public static final int EAGAIN;
    public static final int EALREADY;
    public static final int EBADF;
    public static final int EBADMSG;
    public static final int EBUSY;
    public static final int ECANCELED;
    public static final int ECHILD;
    public static final int ECONNABORTED;
    public static final int ECONNREFUSED;
    public static final int ECONNRESET;
    public static final int EDEADLK;
    public static final int EDESTADDRREQ;
    public static final int EDOM;
    public static final int EDQUOT;
    public static final int EEXIST;
    public static final int EFAULT;
    public static final int EFBIG;
    public static final int EHOSTUNREACH;
    public static final int EIDRM;
    public static final int EILSEQ;
    public static final int EINPROGRESS;
    public static final int EINTR;
    public static final int EINVAL;
    public static final int EIO;
    public static final int EISCONN;
    public static final int EISDIR;
    public static final int ELOOP;
    public static final int EMFILE;
    public static final int EMLINK;
    public static final int EMSGSIZE;
    public static final int EMULTIHOP;
    public static final int ENAMETOOLONG;
    public static final int ENETDOWN;
    public static final int ENETRESET;
    public static final int ENETUNREACH;
    public static final int ENFILE;
    public static final int ENOBUFS;
    public static final int ENODATA;
    public static final int ENODEV;
    public static final int ENOENT;
    public static final int ENOEXEC;
    public static final int ENOLCK;
    public static final int ENOLINK;
    public static final int ENOMEM;
    public static final int ENOMSG;
    public static final int ENONET;
    public static final int ENOPROTOOPT;
    public static final int ENOSPC;
    public static final int ENOSR;
    public static final int ENOSTR;
    public static final int ENOSYS;
    public static final int ENOTCONN;
    public static final int ENOTDIR;
    public static final int ENOTEMPTY;
    public static final int ENOTSOCK;
    public static final int ENOTSUP;
    public static final int ENOTTY;
    public static final int ENXIO;
    public static final int EOPNOTSUPP;
    public static final int EOVERFLOW;
    public static final int EPERM;
    public static final int EPIPE;
    public static final int EPROTO;
    public static final int EPROTONOSUPPORT;
    public static final int EPROTOTYPE;
    public static final int ERANGE;
    public static final int EROFS;
    public static final int ESPIPE;
    public static final int ESRCH;
    public static final int ESTALE;
    public static final int ETIME;
    public static final int ETIMEDOUT;
    public static final int ETXTBSY;
    public static final int ETH_P_ALL;
    public static final int ETH_P_ARP;
    public static final int ETH_P_IP;
    public static final int ETH_P_IPV6;
    /**
     * "Too many users" error.
     * See <a href="https://man7.org/linux/man-pages/man3/errno.3.html">errno(3)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int EUSERS;
    // On Linux, EWOULDBLOCK == EAGAIN. Use EAGAIN instead, to reduce confusion.
    public static final int EXDEV;
    public static final int EXIT_FAILURE;
    public static final int EXIT_SUCCESS;
    public static final int FD_CLOEXEC;
    public static final int FIONREAD;
    public static final int F_DUPFD;
    public static final int F_DUPFD_CLOEXEC;
    public static final int F_GETFD;
    public static final int F_GETFL;

    /**
     * Get the seals set on the file.
     *
     * <p>See <a href="https://man7.org/linux/man-pages/man2/fcntl.2.html">fcntl(2)</a>.
     */
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public static final int F_GET_SEALS;

    /**
     * Seal the file to prevent further write operations.
     */
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public static final int F_SEAL_WRITE;

    /**
     * Seal the file to prevent future write operations.
     */
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_ENABLE_PCC_FRAMEWORK_SUPPORT)
    public static final int F_SEAL_FUTURE_WRITE;

    public static final int F_GETLK = OsConstantsHolder.F_GETLK;
    public static final int F_GETLK64 = OsConstantsHolder.F_GETLK64;
    public static final int F_GETOWN;
    public static final int F_OK;
    public static final int F_RDLCK;
    public static final int F_SETFD;
    public static final int F_SETFL;
    public static final int F_SETLK = OsConstantsHolder.F_SETLK;
    public static final int F_SETLK64 = OsConstantsHolder.F_SETLK64;
    public static final int F_SETLKW = OsConstantsHolder.F_SETLKW;
    public static final int F_SETLKW64 = OsConstantsHolder.F_SETLKW64;
    public static final int F_SETOWN;
    public static final int F_UNLCK;
    public static final int F_WRLCK;
    public static final int ICMP_ECHO;
    public static final int ICMP_ECHOREPLY;
    public static final int ICMP6_ECHO_REQUEST;
    public static final int ICMP6_ECHO_REPLY;
    public static final int IFA_F_DADFAILED;
    public static final int IFA_F_DEPRECATED;
    public static final int IFA_F_HOMEADDRESS;
    public static final int IFA_F_MANAGETEMPADDR;
    public static final int IFA_F_NODAD;
    public static final int IFA_F_NOPREFIXROUTE;
    public static final int IFA_F_OPTIMISTIC;
    public static final int IFA_F_PERMANENT;
    public static final int IFA_F_SECONDARY;
    public static final int IFA_F_TEMPORARY;
    public static final int IFA_F_TENTATIVE;
    public static final int IFF_ALLMULTI;
    public static final int IFF_AUTOMEDIA;
    public static final int IFF_BROADCAST;
    public static final int IFF_DEBUG;
    public static final int IFF_DYNAMIC;
    public static final int IFF_LOOPBACK;
    public static final int IFF_MASTER;
    public static final int IFF_MULTICAST;
    public static final int IFF_NOARP;
    public static final int IFF_NOTRAILERS;
    public static final int IFF_POINTOPOINT;
    public static final int IFF_PORTSEL;
    public static final int IFF_PROMISC;
    public static final int IFF_RUNNING;
    public static final int IFF_SLAVE;
    public static final int IFF_UP;
    public static final int IPPROTO_ICMP;
    public static final int IPPROTO_ICMPV6;
    public static final int IPPROTO_IP;
    public static final int IPPROTO_IPV6;
    public static final int IPPROTO_RAW;
    public static final int IPPROTO_TCP;
    public static final int IPPROTO_UDP;

    /**
     * Encapsulation Security Payload protocol
     *
     * <p>Defined in /uapi/linux/in.h
     */
    public static final int IPPROTO_ESP;

    public static final int IPV6_CHECKSUM;
    public static final int IPV6_MULTICAST_HOPS;
    public static final int IPV6_MULTICAST_IF;
    public static final int IPV6_MULTICAST_LOOP;
    public static final int IPV6_PKTINFO;
    public static final int IPV6_RECVDSTOPTS;
    public static final int IPV6_RECVHOPLIMIT;
    public static final int IPV6_RECVHOPOPTS;
    public static final int IPV6_RECVPKTINFO;
    public static final int IPV6_RECVRTHDR;
    public static final int IPV6_RECVTCLASS;
    public static final int IPV6_TCLASS;
    public static final int IPV6_UNICAST_HOPS;
    public static final int IPV6_V6ONLY;
    /** @hide */
    @UnsupportedAppUsage
    public static final int IP_MULTICAST_ALL;
    public static final int IP_MULTICAST_IF;
    public static final int IP_MULTICAST_LOOP;
    public static final int IP_MULTICAST_TTL;
    /** @hide */
    @UnsupportedAppUsage
    public static final int IP_RECVTOS;
    public static final int IP_TOS;
    public static final int IP_TTL;

    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_NORMAL;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_RANDOM;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_SEQUENTIAL;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_WILLNEED;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_DONTNEED;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_REMOVE;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_DONTFORK;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_DOFORK;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_HWPOISON;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_MERGEABLE;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_UNMERGEABLE;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_SOFT_OFFLINE;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_HUGEPAGE;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_NOHUGEPAGE;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_COLLAPSE;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_DONTDUMP;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_DODUMP;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_FREE;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_WIPEONFORK;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_KEEPONFORK;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_COLD;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_PAGEOUT;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_POPULATE_READ;
    @android.annotation.FlaggedApi(com.android.libcore.Flags.FLAG_MADVISE_API)
    public static final int MADV_POPULATE_WRITE;
    /**
     * Version constant to be used in {@link StructCapUserHeader} with
     * {@link Os#capset(StructCapUserHeader, StructCapUserData[])} and
     * {@link Os#capget(StructCapUserHeader)}.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/capget.2.html">capget(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int _LINUX_CAPABILITY_VERSION_3;
    public static final int MAP_FIXED;
    public static final int MAP_ANONYMOUS;
    /**
     * Flag argument for {@code mmap(long, long, int, int, FileDescriptor, long)}.
     *
     * See <a href="http://man7.org/linux/man-pages/man2/mmap.2.html">mmap(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int MAP_POPULATE;
    public static final int MAP_PRIVATE;
    public static final int MAP_SHARED;
    public static final int MCAST_JOIN_GROUP;
    public static final int MCAST_LEAVE_GROUP;
    public static final int MCAST_JOIN_SOURCE_GROUP;
    public static final int MCAST_LEAVE_SOURCE_GROUP;
    public static final int MCAST_BLOCK_SOURCE;
    public static final int MCAST_UNBLOCK_SOURCE;
    public static final int MCL_CURRENT;
    public static final int MCL_FUTURE;
    public static final int MFD_CLOEXEC;
    public static final int MSG_CTRUNC;
    public static final int MSG_DONTROUTE;
    public static final int MSG_EOR;
    public static final int MSG_OOB;
    public static final int MSG_PEEK;
    public static final int MSG_TRUNC;
    public static final int MSG_WAITALL;
    public static final int MS_ASYNC;
    public static final int MS_INVALIDATE;
    public static final int MS_SYNC;
    public static final int NETLINK_NETFILTER;
    public static final int NETLINK_ROUTE;
    /**
     * SELinux enforces that only system_server and netd may use this netlink socket type.
     */
    public static final int NETLINK_INET_DIAG;

    /**
     * SELinux enforces that only system_server and netd may use this netlink socket type.
     *
     * @see <a href="https://man7.org/linux/man-pages/man7/netlink.7.html">netlink(7)</a>
     */
    public static final int NETLINK_XFRM;

    public static final int NI_DGRAM;
    public static final int NI_NAMEREQD = OsConstantsHolder.NI_NAMEREQD;
    public static final int NI_NOFQDN = OsConstantsHolder.NI_NOFQDN;
    public static final int NI_NUMERICHOST = OsConstantsHolder.NI_NUMERICHOST;
    public static final int NI_NUMERICSERV = OsConstantsHolder.NI_NUMERICSERV;
    public static final int O_ACCMODE;
    public static final int O_APPEND;
    public static final int O_CLOEXEC;
    public static final int O_CREAT;
    /**
     * Flag for {@code Os#open(String, int, int)}.
     *
     * When enabled, tries to minimize cache effects of the I/O to and from this
     * file. In general this will degrade performance, but it is
     * useful in special situations, such as when applications do
     * their own caching. File I/O is done directly to/from
     * user-space buffers. The {@link O_DIRECT} flag on its own makes an
     * effort to transfer data synchronously, but does not give
     * the guarantees of the {@link O_SYNC} flag that data and necessary
     * metadata are transferred. To guarantee synchronous I/O,
     * {@link O_SYNC} must be used in addition to {@link O_DIRECT}.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/open.2.html">open(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int O_DIRECT = OsConstantsHolder.O_DIRECT;
    public static final int O_EXCL;
    public static final int O_NOCTTY;
    public static final int O_NOFOLLOW = OsConstantsHolder.O_NOFOLLOW;
    public static final int O_NONBLOCK;
    public static final int O_RDONLY;
    public static final int O_RDWR;
    public static final int O_SYNC;
    public static final int O_DSYNC;
    public static final int O_TRUNC;
    public static final int O_WRONLY;
    public static final int POLLERR;
    public static final int POLLHUP;
    public static final int POLLIN;
    public static final int POLLNVAL;
    public static final int POLLOUT;
    public static final int POLLPRI;
    public static final int POLLRDBAND;
    public static final int POLLRDNORM;
    public static final int POLLWRBAND;
    public static final int POLLWRNORM;
    /**
     * Reads or changes the ambient capability set of the calling thread.
     * Has to be used as a first argument for {@link Os#prctl(int, long, long, long, long)}.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/prctl.2.html">prctl(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PR_CAP_AMBIENT;
    /**
     * The capability specified in {@code arg3} of {@link Os#prctl(int, long, long, long, long)}
     * is added to the ambient set. The specified capability must already
     * be present in both the permitted and the inheritable sets of the process.
     * Has to be used as a second argument for {@link Os#prctl(int, long, long, long, long)}.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/prctl.2.html">prctl(2)</a>.
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int PR_CAP_AMBIENT_RAISE;
    public static final int PR_GET_DUMPABLE;
    public static final int PR_SET_DUMPABLE;
    public static final int PR_SET_NO_NEW_PRIVS;
    public static final int PROT_EXEC;
    public static final int PROT_NONE;
    public static final int PROT_READ;
    public static final int PROT_WRITE;
    public static final int R_OK;
    /**
     * Specifies a value one greater than the maximum file
     * descriptor number that can be opened by this process.
     *
     * <p>Attempts ({@link Os#open(String, int, int)}, {@link Os#pipe()},
     * {@link Os#dup(java.io.FileDescriptor)}, etc.) to exceed this
     * limit yield the error {@link EMFILE}.
     *
     * See <a href="https://man7.org/linux/man-pages/man3/vlimit.3.html">getrlimit(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int RLIMIT_NOFILE;
    /** @hide */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int RLIMIT_RTPRIO;
    public static final int RT_SCOPE_HOST;
    public static final int RT_SCOPE_LINK;
    public static final int RT_SCOPE_NOWHERE;
    public static final int RT_SCOPE_SITE;
    public static final int RT_SCOPE_UNIVERSE;
    /**
     * Bitmask for IPv4 addresses add/delete events multicast groups mask.
     * Used in {@link NetlinkSocketAddress}.
     *
     * See <a href="https://man7.org/linux/man-pages/man7/netlink.7.html">netlink(7)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int RTMGRP_IPV4_IFADDR;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_IPV4_MROUTE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_IPV4_ROUTE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_IPV4_RULE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_IFADDR;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_IFINFO;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_MROUTE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_PREFIX;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_IPV6_ROUTE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_LINK;
    public static final int RTMGRP_NEIGH;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_NOTIFY;
    /** @hide */
    @UnsupportedAppUsage
    public static final int RTMGRP_TC;
    public static final int SEEK_CUR;
    public static final int SEEK_END;
    public static final int SEEK_SET;
    public static final int SHUT_RD;
    public static final int SHUT_RDWR;
    public static final int SHUT_WR;
    public static final int SIGABRT;
    public static final int SIGALRM;
    public static final int SIGBUS;
    public static final int SIGCHLD;
    public static final int SIGCONT;
    public static final int SIGFPE;
    public static final int SIGHUP;
    public static final int SIGILL;
    public static final int SIGINT;
    public static final int SIGIO;
    public static final int SIGKILL;
    public static final int SIGPIPE;
    public static final int SIGPROF;
    public static final int SIGPWR;
    public static final int SIGQUIT;
    public static final int SIGRTMAX;
    public static final int SIGRTMIN = OsConstantsHolder.SIGRTMIN;
    public static final int SIGSEGV;
    public static final int SIGSTKFLT;
    public static final int SIGSTOP;
    public static final int SIGSYS;
    public static final int SIGTERM;
    public static final int SIGTRAP;
    public static final int SIGTSTP;
    public static final int SIGTTIN;
    public static final int SIGTTOU;
    public static final int SIGURG;
    public static final int SIGUSR1;
    public static final int SIGUSR2;
    public static final int SIGVTALRM;
    public static final int SIGWINCH;
    public static final int SIGXCPU;
    public static final int SIGXFSZ;
    public static final int SIOCGIFADDR;
    public static final int SIOCGIFBRDADDR;
    public static final int SIOCGIFDSTADDR;
    public static final int SIOCGIFNETMASK;

    /**
     * Set the close-on-exec ({@code FD_CLOEXEC}) flag on the new file
     * descriptor created by {@link Os#socket(int,int,int)} or
     * {@link Os#socketpair(int,int,int,java.io.FileDescriptor,java.io.FileDescriptor)}.
     * See the description of the O_CLOEXEC flag in
     * <a href="http://man7.org/linux/man-pages/man2/open.2.html">open(2)</a>
     * for reasons why this may be useful.
     *
     * <p>Applications wishing to make use of this flag on older API versions
     * may use {@link #O_CLOEXEC} instead. On Android, {@code O_CLOEXEC} and
     * {@code SOCK_CLOEXEC} are the same value.
     */
    public static final int SOCK_CLOEXEC;
    public static final int SOCK_DGRAM;

    /**
     * Set the O_NONBLOCK file status flag on the file descriptor
     * created by {@link Os#socket(int,int,int)} or
     * {@link Os#socketpair(int,int,int,java.io.FileDescriptor,java.io.FileDescriptor)}.
     *
     * <p>Applications wishing to make use of this flag on older API versions
     * may use {@link #O_NONBLOCK} instead. On Android, {@code O_NONBLOCK}
     * and {@code SOCK_NONBLOCK} are the same value.
     */
    public static final int SOCK_NONBLOCK;
    public static final int SOCK_RAW;
    public static final int SOCK_SEQPACKET;
    public static final int SOCK_STREAM;
    public static final int SOL_SOCKET;
    public static final int SOL_UDP;
    public static final int SOL_PACKET;
    public static final int SO_BINDTODEVICE;
    public static final int SO_BROADCAST;
    public static final int SO_DEBUG;
    /** @hide */
    @UnsupportedAppUsage
    public static final int SO_DOMAIN;
    public static final int SO_DONTROUTE;
    public static final int SO_ERROR;
    public static final int SO_KEEPALIVE;
    public static final int SO_LINGER;
    public static final int SO_OOBINLINE;
    public static final int SO_PASSCRED;
    public static final int SO_PEERCRED;
    /** @hide */
    @UnsupportedAppUsage
    public static final int SO_PROTOCOL;
    public static final int SO_RCVBUF;
    public static final int SO_RCVLOWAT;
    public static final int SO_RCVTIMEO;
    public static final int SO_REUSEADDR;
    public static final int SO_SNDBUF;
    public static final int SO_SNDLOWAT;
    public static final int SO_SNDTIMEO;
    public static final int SO_TYPE;
    public static final int PACKET_IGNORE_OUTGOING;
    /**
     * Bitmask for flags argument of
     * {@link splice(java.io.FileDescriptor, Int64Ref, FileDescriptor, Int64Ref, long, int)}.
     *
     * Attempt to move pages instead of copying.  This is only a
     * hint to the kernel: pages may still be copied if the
     * kernel cannot move the pages from the pipe, or if the pipe
     * buffers don't refer to full pages.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/splice.2.html">splice(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int SPLICE_F_MOVE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int SPLICE_F_NONBLOCK;
    /**
     * Bitmask for flags argument of
     * {@link splice(java.io.FileDescriptor, Int64Ref, FileDescriptor, Int64Ref, long, int)}.
     *
     * <p>Indicates that more data will be coming in a subsequent splice. This is
     * a helpful hint when the {@code fdOut} refers to a socket.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/splice.2.html">splice(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int SPLICE_F_MORE;
    public static final int STDERR_FILENO;
    public static final int STDIN_FILENO;
    public static final int STDOUT_FILENO;
    public static final int ST_MANDLOCK;
    public static final int ST_NOATIME;
    public static final int ST_NODEV;
    public static final int ST_NODIRATIME;
    public static final int ST_NOEXEC;
    public static final int ST_NOSUID;
    public static final int ST_RDONLY;
    public static final int ST_RELATIME;
    public static final int ST_SYNCHRONOUS;
    public static final int S_IFBLK;
    public static final int S_IFCHR;
    public static final int S_IFDIR;
    public static final int S_IFIFO;
    public static final int S_IFLNK;
    public static final int S_IFMT;
    public static final int S_IFREG;
    public static final int S_IFSOCK;
    public static final int S_IRGRP;
    public static final int S_IROTH;
    public static final int S_IRUSR;
    public static final int S_IRWXG;
    public static final int S_IRWXO;
    public static final int S_IRWXU;
    public static final int S_ISGID;
    public static final int S_ISUID;
    public static final int S_ISVTX;
    public static final int S_IWGRP;
    public static final int S_IWOTH;
    public static final int S_IWUSR;
    public static final int S_IXGRP;
    public static final int S_IXOTH;
    public static final int S_IXUSR;
    public static final int TCP_NODELAY;
    public static final int TCP_USER_TIMEOUT;
    public static final int UDP_GRO;
    public static final int UDP_SEGMENT;
    /**
     * Get the number of bytes in the output buffer.
     *
     * See <a href="https://man7.org/linux/man-pages/man2/ioctl.2.html">ioctl(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int TIOCOUTQ;
    /**
     * Sockopt option to encapsulate ESP packets in UDP.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int UDP_ENCAP;
    /** @hide */
    @UnsupportedAppUsage
    public static final int UDP_ENCAP_ESPINUDP_NON_IKE;
    /** @hide */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int UDP_ENCAP_ESPINUDP;
    /** @hide */
    @UnsupportedAppUsage
    public static final int UNIX_PATH_MAX;
    public static final int WCONTINUED;
    public static final int WEXITED;
    public static final int WNOHANG;
    public static final int WNOWAIT;
    public static final int WSTOPPED;
    public static final int WUNTRACED;
    public static final int W_OK;
    /**
     * {@code flags} option for {@link Os#setxattr(String, String, byte[], int)}.
     *
     * <p>Performs a pure create, which fails if the named attribute exists already.
     *
     * See <a href="http://man7.org/linux/man-pages/man2/setxattr.2.html">setxattr(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int XATTR_CREATE;
    /**
     * {@code flags} option for {@link Os#setxattr(String, String, byte[], int)}.
     *
     * <p>Perform a pure replace operation, which fails if the named attribute
     * does not already exist.
     *
     * See <a href="http://man7.org/linux/man-pages/man2/setxattr.2.html">setxattr(2)</a>.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int XATTR_REPLACE;
    public static final int X_OK;
    public static final int _SC_2_CHAR_TERM = OsConstantsHolder._SC_2_CHAR_TERM;
    public static final int _SC_2_C_BIND = OsConstantsHolder._SC_2_C_BIND;
    public static final int _SC_2_C_DEV = OsConstantsHolder._SC_2_C_DEV;
    public static final int _SC_2_C_VERSION = OsConstantsHolder._SC_2_C_VERSION;
    public static final int _SC_2_FORT_DEV = OsConstantsHolder._SC_2_FORT_DEV;
    public static final int _SC_2_FORT_RUN = OsConstantsHolder._SC_2_FORT_RUN;
    public static final int _SC_2_LOCALEDEF = OsConstantsHolder._SC_2_LOCALEDEF;
    public static final int _SC_2_SW_DEV = OsConstantsHolder._SC_2_SW_DEV;
    public static final int _SC_2_UPE = OsConstantsHolder._SC_2_UPE;
    public static final int _SC_2_VERSION = OsConstantsHolder._SC_2_VERSION;
    public static final int _SC_AIO_LISTIO_MAX = OsConstantsHolder._SC_AIO_LISTIO_MAX;
    public static final int _SC_AIO_MAX = OsConstantsHolder._SC_AIO_MAX;
    public static final int _SC_AIO_PRIO_DELTA_MAX = OsConstantsHolder._SC_AIO_PRIO_DELTA_MAX;
    public static final int _SC_ARG_MAX = OsConstantsHolder._SC_ARG_MAX;
    public static final int _SC_ASYNCHRONOUS_IO = OsConstantsHolder._SC_ASYNCHRONOUS_IO;
    public static final int _SC_ATEXIT_MAX = OsConstantsHolder._SC_ATEXIT_MAX;
    public static final int _SC_AVPHYS_PAGES = OsConstantsHolder._SC_AVPHYS_PAGES;
    public static final int _SC_BC_BASE_MAX = OsConstantsHolder._SC_BC_BASE_MAX;
    public static final int _SC_BC_DIM_MAX = OsConstantsHolder._SC_BC_DIM_MAX;
    public static final int _SC_BC_SCALE_MAX = OsConstantsHolder._SC_BC_SCALE_MAX;
    public static final int _SC_BC_STRING_MAX = OsConstantsHolder._SC_BC_STRING_MAX;
    public static final int _SC_CHILD_MAX = OsConstantsHolder._SC_CHILD_MAX;
    public static final int _SC_CLK_TCK = OsConstantsHolder._SC_CLK_TCK;
    public static final int _SC_COLL_WEIGHTS_MAX = OsConstantsHolder._SC_COLL_WEIGHTS_MAX;
    public static final int _SC_DELAYTIMER_MAX = OsConstantsHolder._SC_DELAYTIMER_MAX;
    public static final int _SC_EXPR_NEST_MAX = OsConstantsHolder._SC_EXPR_NEST_MAX;
    public static final int _SC_FSYNC = OsConstantsHolder._SC_FSYNC;
    public static final int _SC_GETGR_R_SIZE_MAX = OsConstantsHolder._SC_GETGR_R_SIZE_MAX;
    public static final int _SC_GETPW_R_SIZE_MAX = OsConstantsHolder._SC_GETPW_R_SIZE_MAX;
    public static final int _SC_IOV_MAX = OsConstantsHolder._SC_IOV_MAX;
    public static final int _SC_JOB_CONTROL = OsConstantsHolder._SC_JOB_CONTROL;
    public static final int _SC_LINE_MAX = OsConstantsHolder._SC_LINE_MAX;
    public static final int _SC_LOGIN_NAME_MAX = OsConstantsHolder._SC_LOGIN_NAME_MAX;
    public static final int _SC_MAPPED_FILES = OsConstantsHolder._SC_MAPPED_FILES;
    public static final int _SC_MEMLOCK = OsConstantsHolder._SC_MEMLOCK;
    public static final int _SC_MEMLOCK_RANGE = OsConstantsHolder._SC_MEMLOCK_RANGE;
    public static final int _SC_MEMORY_PROTECTION = OsConstantsHolder._SC_MEMORY_PROTECTION;
    public static final int _SC_MESSAGE_PASSING = OsConstantsHolder._SC_MESSAGE_PASSING;
    public static final int _SC_MQ_OPEN_MAX = OsConstantsHolder._SC_MQ_OPEN_MAX;
    public static final int _SC_MQ_PRIO_MAX = OsConstantsHolder._SC_MQ_PRIO_MAX;
    public static final int _SC_NGROUPS_MAX = OsConstantsHolder._SC_NGROUPS_MAX;
    public static final int _SC_NPROCESSORS_CONF = OsConstantsHolder._SC_NPROCESSORS_CONF;
    public static final int _SC_NPROCESSORS_ONLN = OsConstantsHolder._SC_NPROCESSORS_ONLN;
    public static final int _SC_OPEN_MAX = OsConstantsHolder._SC_OPEN_MAX;
    public static final int _SC_PAGESIZE = OsConstantsHolder._SC_PAGESIZE;
    public static final int _SC_PAGE_SIZE = OsConstantsHolder._SC_PAGE_SIZE;
    public static final int _SC_PASS_MAX = OsConstantsHolder._SC_PASS_MAX;
    public static final int _SC_PHYS_PAGES = OsConstantsHolder._SC_PHYS_PAGES;
    public static final int _SC_PRIORITIZED_IO = OsConstantsHolder._SC_PRIORITIZED_IO;
    public static final int _SC_PRIORITY_SCHEDULING = OsConstantsHolder._SC_PRIORITY_SCHEDULING;
    public static final int _SC_REALTIME_SIGNALS = OsConstantsHolder._SC_REALTIME_SIGNALS;
    public static final int _SC_RE_DUP_MAX = OsConstantsHolder._SC_RE_DUP_MAX;
    public static final int _SC_RTSIG_MAX = OsConstantsHolder._SC_RTSIG_MAX;
    public static final int _SC_SAVED_IDS = OsConstantsHolder._SC_SAVED_IDS;
    public static final int _SC_SEMAPHORES = OsConstantsHolder._SC_SEMAPHORES;
    public static final int _SC_SEM_NSEMS_MAX = OsConstantsHolder._SC_SEM_NSEMS_MAX;
    public static final int _SC_SEM_VALUE_MAX = OsConstantsHolder._SC_SEM_VALUE_MAX;
    public static final int _SC_SHARED_MEMORY_OBJECTS = OsConstantsHolder._SC_SHARED_MEMORY_OBJECTS;
    public static final int _SC_SIGQUEUE_MAX = OsConstantsHolder._SC_SIGQUEUE_MAX;
    public static final int _SC_STREAM_MAX = OsConstantsHolder._SC_STREAM_MAX;
    public static final int _SC_SYNCHRONIZED_IO = OsConstantsHolder._SC_SYNCHRONIZED_IO;
    public static final int _SC_THREADS = OsConstantsHolder._SC_THREADS;
    public static final int _SC_THREAD_ATTR_STACKADDR = OsConstantsHolder._SC_THREAD_ATTR_STACKADDR;
    public static final int _SC_THREAD_ATTR_STACKSIZE = OsConstantsHolder._SC_THREAD_ATTR_STACKSIZE;
    public static final int _SC_THREAD_DESTRUCTOR_ITERATIONS = OsConstantsHolder._SC_THREAD_DESTRUCTOR_ITERATIONS;
    public static final int _SC_THREAD_KEYS_MAX = OsConstantsHolder._SC_THREAD_KEYS_MAX;
    public static final int _SC_THREAD_PRIORITY_SCHEDULING = OsConstantsHolder._SC_THREAD_PRIORITY_SCHEDULING;
    public static final int _SC_THREAD_PRIO_INHERIT = OsConstantsHolder._SC_THREAD_PRIO_INHERIT;
    public static final int _SC_THREAD_PRIO_PROTECT = OsConstantsHolder._SC_THREAD_PRIO_PROTECT;
    public static final int _SC_THREAD_SAFE_FUNCTIONS = OsConstantsHolder._SC_THREAD_SAFE_FUNCTIONS;
    public static final int _SC_THREAD_STACK_MIN = OsConstantsHolder._SC_THREAD_STACK_MIN;
    public static final int _SC_THREAD_THREADS_MAX = OsConstantsHolder._SC_THREAD_THREADS_MAX;
    public static final int _SC_TIMERS = OsConstantsHolder._SC_TIMERS;
    public static final int _SC_TIMER_MAX = OsConstantsHolder._SC_TIMER_MAX;
    public static final int _SC_TTY_NAME_MAX = OsConstantsHolder._SC_TTY_NAME_MAX;
    public static final int _SC_TZNAME_MAX = OsConstantsHolder._SC_TZNAME_MAX;
    public static final int _SC_VERSION = OsConstantsHolder._SC_VERSION;
    public static final int _SC_XBS5_ILP32_OFF32 = OsConstantsHolder._SC_XBS5_ILP32_OFF32;
    public static final int _SC_XBS5_ILP32_OFFBIG = OsConstantsHolder._SC_XBS5_ILP32_OFFBIG;
    public static final int _SC_XBS5_LP64_OFF64 = OsConstantsHolder._SC_XBS5_LP64_OFF64;
    public static final int _SC_XBS5_LPBIG_OFFBIG = OsConstantsHolder._SC_XBS5_LPBIG_OFFBIG;
    public static final int _SC_XOPEN_CRYPT = OsConstantsHolder._SC_XOPEN_CRYPT;
    public static final int _SC_XOPEN_ENH_I18N = OsConstantsHolder._SC_XOPEN_ENH_I18N;
    public static final int _SC_XOPEN_LEGACY = OsConstantsHolder._SC_XOPEN_LEGACY;
    public static final int _SC_XOPEN_REALTIME = OsConstantsHolder._SC_XOPEN_REALTIME;
    public static final int _SC_XOPEN_REALTIME_THREADS = OsConstantsHolder._SC_XOPEN_REALTIME_THREADS;
    public static final int _SC_XOPEN_SHM = OsConstantsHolder._SC_XOPEN_SHM;
    public static final int _SC_XOPEN_UNIX = OsConstantsHolder._SC_XOPEN_UNIX;
    public static final int _SC_XOPEN_VERSION = OsConstantsHolder._SC_XOPEN_VERSION;
    public static final int _SC_XOPEN_XCU_VERSION = OsConstantsHolder._SC_XOPEN_XCU_VERSION;

    /**
     * Returns the string name of a getaddrinfo(3) error value.
     * For example, "EAI_AGAIN".
     */
    public static String gaiName(int error) {
        if (error == EAI_AGAIN) {
            return "EAI_AGAIN";
        }
        if (error == EAI_BADFLAGS) {
            return "EAI_BADFLAGS";
        }
        if (error == EAI_FAIL) {
            return "EAI_FAIL";
        }
        if (error == EAI_FAMILY) {
            return "EAI_FAMILY";
        }
        if (error == EAI_MEMORY) {
            return "EAI_MEMORY";
        }
        if (error == EAI_NODATA) {
            return "EAI_NODATA";
        }
        if (error == EAI_NONAME) {
            return "EAI_NONAME";
        }
        if (error == EAI_OVERFLOW) {
            return "EAI_OVERFLOW";
        }
        if (error == EAI_SERVICE) {
            return "EAI_SERVICE";
        }
        if (error == EAI_SOCKTYPE) {
            return "EAI_SOCKTYPE";
        }
        if (error == EAI_SYSTEM) {
            return "EAI_SYSTEM";
        }
        return null;
    }

    /**
     * Returns the string name of an errno value.
     * For example, "EACCES". See {@link Os#strerror} for human-readable errno descriptions.
     */
    public static String errnoName(int errno) {
        if (errno == E2BIG) {
            return "E2BIG";
        }
        if (errno == EACCES) {
            return "EACCES";
        }
        if (errno == EADDRINUSE) {
            return "EADDRINUSE";
        }
        if (errno == EADDRNOTAVAIL) {
            return "EADDRNOTAVAIL";
        }
        if (errno == EAFNOSUPPORT) {
            return "EAFNOSUPPORT";
        }
        if (errno == EAGAIN) {
            return "EAGAIN";
        }
        if (errno == EALREADY) {
            return "EALREADY";
        }
        if (errno == EBADF) {
            return "EBADF";
        }
        if (errno == EBADMSG) {
            return "EBADMSG";
        }
        if (errno == EBUSY) {
            return "EBUSY";
        }
        if (errno == ECANCELED) {
            return "ECANCELED";
        }
        if (errno == ECHILD) {
            return "ECHILD";
        }
        if (errno == ECONNABORTED) {
            return "ECONNABORTED";
        }
        if (errno == ECONNREFUSED) {
            return "ECONNREFUSED";
        }
        if (errno == ECONNRESET) {
            return "ECONNRESET";
        }
        if (errno == EDEADLK) {
            return "EDEADLK";
        }
        if (errno == EDESTADDRREQ) {
            return "EDESTADDRREQ";
        }
        if (errno == EDOM) {
            return "EDOM";
        }
        if (errno == EDQUOT) {
            return "EDQUOT";
        }
        if (errno == EEXIST) {
            return "EEXIST";
        }
        if (errno == EFAULT) {
            return "EFAULT";
        }
        if (errno == EFBIG) {
            return "EFBIG";
        }
        if (errno == EHOSTUNREACH) {
            return "EHOSTUNREACH";
        }
        if (errno == EIDRM) {
            return "EIDRM";
        }
        if (errno == EILSEQ) {
            return "EILSEQ";
        }
        if (errno == EINPROGRESS) {
            return "EINPROGRESS";
        }
        if (errno == EINTR) {
            return "EINTR";
        }
        if (errno == EINVAL) {
            return "EINVAL";
        }
        if (errno == EIO) {
            return "EIO";
        }
        if (errno == EISCONN) {
            return "EISCONN";
        }
        if (errno == EISDIR) {
            return "EISDIR";
        }
        if (errno == ELOOP) {
            return "ELOOP";
        }
        if (errno == EMFILE) {
            return "EMFILE";
        }
        if (errno == EMLINK) {
            return "EMLINK";
        }
        if (errno == EMSGSIZE) {
            return "EMSGSIZE";
        }
        if (errno == EMULTIHOP) {
            return "EMULTIHOP";
        }
        if (errno == ENAMETOOLONG) {
            return "ENAMETOOLONG";
        }
        if (errno == ENETDOWN) {
            return "ENETDOWN";
        }
        if (errno == ENETRESET) {
            return "ENETRESET";
        }
        if (errno == ENETUNREACH) {
            return "ENETUNREACH";
        }
        if (errno == ENFILE) {
            return "ENFILE";
        }
        if (errno == ENOBUFS) {
            return "ENOBUFS";
        }
        if (errno == ENODATA) {
            return "ENODATA";
        }
        if (errno == ENODEV) {
            return "ENODEV";
        }
        if (errno == ENOENT) {
            return "ENOENT";
        }
        if (errno == ENOEXEC) {
            return "ENOEXEC";
        }
        if (errno == ENOLCK) {
            return "ENOLCK";
        }
        if (errno == ENOLINK) {
            return "ENOLINK";
        }
        if (errno == ENOMEM) {
            return "ENOMEM";
        }
        if (errno == ENOMSG) {
            return "ENOMSG";
        }
        if (errno == ENONET) {
            return "ENONET";
        }
        if (errno == ENOPROTOOPT) {
            return "ENOPROTOOPT";
        }
        if (errno == ENOSPC) {
            return "ENOSPC";
        }
        if (errno == ENOSR) {
            return "ENOSR";
        }
        if (errno == ENOSTR) {
            return "ENOSTR";
        }
        if (errno == ENOSYS) {
            return "ENOSYS";
        }
        if (errno == ENOTCONN) {
            return "ENOTCONN";
        }
        if (errno == ENOTDIR) {
            return "ENOTDIR";
        }
        if (errno == ENOTEMPTY) {
            return "ENOTEMPTY";
        }
        if (errno == ENOTSOCK) {
            return "ENOTSOCK";
        }
        if (errno == ENOTSUP) {
            return "ENOTSUP";
        }
        if (errno == ENOTTY) {
            return "ENOTTY";
        }
        if (errno == ENXIO) {
            return "ENXIO";
        }
        if (errno == EOPNOTSUPP) {
            return "EOPNOTSUPP";
        }
        if (errno == EOVERFLOW) {
            return "EOVERFLOW";
        }
        if (errno == EPERM) {
            return "EPERM";
        }
        if (errno == EPIPE) {
            return "EPIPE";
        }
        if (errno == EPROTO) {
            return "EPROTO";
        }
        if (errno == EPROTONOSUPPORT) {
            return "EPROTONOSUPPORT";
        }
        if (errno == EPROTOTYPE) {
            return "EPROTOTYPE";
        }
        if (errno == ERANGE) {
            return "ERANGE";
        }
        if (errno == EROFS) {
            return "EROFS";
        }
        if (errno == ESPIPE) {
            return "ESPIPE";
        }
        if (errno == ESRCH) {
            return "ESRCH";
        }
        if (errno == ESTALE) {
            return "ESTALE";
        }
        if (errno == ETIME) {
            return "ETIME";
        }
        if (errno == ETIMEDOUT) {
            return "ETIMEDOUT";
        }
        if (errno == ETXTBSY) {
            return "ETXTBSY";
        }
        if (errno == EXDEV) {
            return "EXDEV";
        }
        return null;
    }

    static {
        // Setting values of these fields at their definition site will make metalava expose them
        // in the APIs. A sensible Linux port is unlikely to change values of these constants.
        // However, on macOS AF_INET6 is 30. macOS support was dropped, but I am using that as an
        // excuse to not promise more than we currently do.

        // These values are taken from include/linux/socket.h
        AF_INET = 2;
        AF_INET6 = 10;
        AF_NETLINK = 16;
        AF_PACKET = 17;
        AF_UNIX = 1;
        AF_VSOCK = 40;
        AF_UNSPEC = 0;

        ARPHRD_ETHER = 1;
        ARPHRD_LOOPBACK = 772;
        ASHMEM_GET_PROT_MASK = 30470;

        VMADDR_PORT_ANY = -1;
        VMADDR_CID_ANY = -1;
        VMADDR_CID_LOCAL = 1;
        VMADDR_CID_HOST = 2;

        CAP_AUDIT_CONTROL = 30;
        CAP_AUDIT_WRITE = 29;
        CAP_BLOCK_SUSPEND = 36;
        CAP_CHOWN = 0;
        CAP_DAC_OVERRIDE = 1;
        CAP_DAC_READ_SEARCH = 2;
        CAP_FOWNER = 3;
        CAP_FSETID = 4;
        CAP_IPC_LOCK = 14;
        CAP_IPC_OWNER = 15;
        CAP_KILL = 5;
        CAP_LAST_CAP = 40;
        CAP_LEASE = 28;
        CAP_LINUX_IMMUTABLE = 9;
        CAP_MAC_ADMIN = 33;
        CAP_MAC_OVERRIDE = 32;
        CAP_MKNOD = 27;
        CAP_NET_ADMIN = 12;
        CAP_NET_BIND_SERVICE = 10;
        CAP_NET_BROADCAST = 11;
        CAP_NET_RAW = 13;
        CAP_SETFCAP = 31;
        CAP_SETGID = 6;
        CAP_SETPCAP = 8;
        CAP_SETUID = 7;
        CAP_SYS_ADMIN = 21;
        CAP_SYS_BOOT = 22;
        CAP_SYS_CHROOT = 18;
        CAP_SYSLOG = 34;
        CAP_SYS_MODULE = 16;
        CAP_SYS_NICE = 23;
        CAP_SYS_PACCT = 20;
        CAP_SYS_PTRACE = 19;
        CAP_SYS_RAWIO = 17;
        CAP_SYS_RESOURCE = 24;
        CAP_SYS_TIME = 25;
        CAP_SYS_TTY_CONFIG = 26;
        CAP_WAKE_ALARM = 35;
        _LINUX_CAPABILITY_VERSION_3 = 0x20080522;

        // Defined in POSIX: https://pubs.opengroup.org/onlinepubs/9799919799/functions/stdin.html
        STDIN_FILENO = 0;
        STDOUT_FILENO = 1;
        STDERR_FILENO = 2;

        // include/uapi/asm-generic/errno.h and include/uapi/asm-generic/errno-base.h
        // ENOTSUP is not mentioned there, but on Linux it is the same as EOPNOTSUPP.
        E2BIG = 7;
        EACCES = 13;
        EADDRINUSE = 98;
        EADDRNOTAVAIL = 99;
        EAFNOSUPPORT = 97;
        EAGAIN = 11;
        EALREADY = 114;
        EBADF = 9;
        EBADMSG = 74;
        EBUSY = 16;
        ECANCELED = 125;
        ECHILD = 10;
        ECONNABORTED = 103;
        ECONNREFUSED = 111;
        ECONNRESET = 104;
        EDEADLK = 35;
        EDESTADDRREQ = 89;
        EDOM = 33;
        EDQUOT = 122;
        EEXIST = 17;
        EFAULT = 14;
        EFBIG = 27;
        EHOSTUNREACH = 113;
        EIDRM = 43;
        EILSEQ = 84;
        EINPROGRESS = 115;
        EINTR = 4;
        EINVAL = 22;
        EIO = 5;
        EISCONN = 106;
        EISDIR = 21;
        ELOOP = 40;
        EMFILE = 24;
        EMLINK = 31;
        EMSGSIZE = 90;
        EMULTIHOP = 72;
        ENAMETOOLONG = 36;
        ENETDOWN = 100;
        ENETRESET = 102;
        ENETUNREACH = 101;
        ENFILE = 23;
        ENOBUFS = 105;
        ENODATA = 61;
        ENODEV = 19;
        ENOENT = 2;
        ENOEXEC = 8;
        ENOLCK = 37;
        ENOLINK = 67;
        ENOMEM = 12;
        ENOMSG = 42;
        ENONET = 64;
        ENOPROTOOPT = 92;
        ENOSPC = 28;
        ENOSR = 63;
        ENOSTR = 60;
        ENOSYS = 38;
        ENOTCONN = 107;
        ENOTDIR = 20;
        ENOTEMPTY = 39;
        ENOTSOCK = 88;
        ENOTSUP = 95;
        ENOTTY = 25;
        ENXIO = 6;
        EOPNOTSUPP = 95;
        EOVERFLOW = 75;
        EPERM = 1;
        EPIPE = 32;
        EPROTO = 71;
        EPROTONOSUPPORT = 93;
        EPROTOTYPE = 91;
        ERANGE = 34;
        EROFS = 30;
        ESPIPE = 29;
        ESRCH = 3;
        ESTALE = 116;
        ETIME = 62;
        ETIMEDOUT = 110;
        ETXTBSY = 26;
        EUSERS = 87;
        EXDEV = 18;

        // Defined in POSIX.
        EXIT_SUCCESS = 0;
        // POSIX only says that it is "between 1 and 255", but in practice it is always 1.
        EXIT_FAILURE = 1;

        ETH_P_ALL = 0x0003;
        ETH_P_ARP = 0x0806;
        ETH_P_IP = 0x0800;
        ETH_P_IPV6 = 0x86DD;

        FD_CLOEXEC = 1;
        FIONREAD = 21531;
        F_DUPFD = 0;
        F_DUPFD_CLOEXEC = 1030;
        F_GETFD = 1;
        F_GETFL = 3;
        F_GETOWN = 9;
        F_GET_SEALS = 1034;
        F_SEAL_WRITE = 8;
        F_SEAL_FUTURE_WRITE = 16;
        F_OK = 0;
        R_OK = 4;
        F_RDLCK = 0;
        F_SETFD = 2;
        F_SETFL = 4;
        F_SETOWN = 8;
        F_UNLCK = 2;
        F_WRLCK = 1;

        ICMP_ECHO = 8;
        ICMP_ECHOREPLY = 0;
        // These valued are defined in https://datatracker.ietf.org/doc/html/rfc2463.
        ICMP6_ECHO_REQUEST = 128;
        ICMP6_ECHO_REPLY = 129;

        IFA_F_DADFAILED = 8;
        IFA_F_DEPRECATED = 32;
        IFA_F_HOMEADDRESS = 16;
        IFA_F_MANAGETEMPADDR = 256;
        IFA_F_NODAD = 2;
        IFA_F_NOPREFIXROUTE = 512;
        IFA_F_OPTIMISTIC = 4;
        IFA_F_PERMANENT = 128;
        IFA_F_SECONDARY = 1;
        IFA_F_TEMPORARY = 1;
        IFA_F_TENTATIVE = 64;

        IFF_ALLMULTI = 512;
        IFF_AUTOMEDIA = 16384;
        IFF_BROADCAST = 2;
        IFF_DEBUG = 4;
        IFF_DYNAMIC = 32768;
        IFF_LOOPBACK = 8;
        IFF_MASTER = 1024;
        IFF_MULTICAST = 4096;
        IFF_NOARP = 128;
        IFF_NOTRAILERS = 32;
        IFF_POINTOPOINT = 16;
        IFF_PORTSEL = 8192;
        IFF_PROMISC = 256;
        IFF_RUNNING = 64;
        IFF_SLAVE = 2048;
        IFF_UP = 1;

        IPPROTO_ICMP = 1;
        IPPROTO_ICMPV6 = 58;
        IPPROTO_IP = 0;
        IPPROTO_IPV6 = 41;
        IPPROTO_RAW = 255;
        IPPROTO_TCP = 6;
        IPPROTO_UDP = 17;
        IPPROTO_ESP = 50;
        IPV6_CHECKSUM = 7;
        IPV6_MULTICAST_HOPS = 18;
        IPV6_MULTICAST_IF = 17;
        IPV6_MULTICAST_LOOP = 19;
        IPV6_PKTINFO = 50;
        IPV6_RECVDSTOPTS = 58;
        IPV6_RECVHOPLIMIT = 51;
        IPV6_RECVHOPOPTS = 53;
        IPV6_RECVPKTINFO = 49;
        IPV6_RECVRTHDR = 56;
        IPV6_RECVTCLASS = 66;
        IPV6_TCLASS = 67;
        IPV6_UNICAST_HOPS = 16;
        IPV6_V6ONLY = 26;
        IP_MULTICAST_ALL = 49;
        IP_MULTICAST_IF = 32;
        IP_MULTICAST_LOOP = 34;
        IP_MULTICAST_TTL = 33;
        IP_RECVTOS = 13;
        IP_TOS = 1;
        IP_TTL = 2;

        MADV_NORMAL = 0;
        MADV_RANDOM = 1;
        MADV_SEQUENTIAL = 2;
        MADV_WILLNEED = 3;
        MADV_DONTNEED = 4;
        MADV_REMOVE = 9;
        MADV_DONTFORK = 10;
        MADV_DOFORK = 11;
        MADV_HWPOISON = 100;
        MADV_MERGEABLE = 12;
        MADV_UNMERGEABLE = 13;
        MADV_SOFT_OFFLINE = 101;
        MADV_HUGEPAGE = 14;
        MADV_NOHUGEPAGE = 15;
        MADV_COLLAPSE = 25;
        MADV_DONTDUMP = 16;
        MADV_DODUMP = 17;
        MADV_FREE = 8;
        MADV_WIPEONFORK = 18;
        MADV_KEEPONFORK = 19;
        MADV_COLD = 20;
        MADV_PAGEOUT = 21;
        MADV_POPULATE_READ = 22;
        MADV_POPULATE_WRITE = 23;

        MAP_FIXED = 16;
        MAP_ANONYMOUS = 32;
        MAP_POPULATE = 32768;
        MAP_PRIVATE = 2;
        MAP_SHARED = 1;

        MCAST_JOIN_GROUP = 42;
        MCAST_LEAVE_GROUP = 45;
        MCAST_JOIN_SOURCE_GROUP = 46;
        MCAST_LEAVE_SOURCE_GROUP = 47;
        MCAST_BLOCK_SOURCE = 43;
        MCAST_UNBLOCK_SOURCE = 44;

        MCL_CURRENT = 1;
        MCL_FUTURE = 2;

        MFD_CLOEXEC = 1;

        MSG_CTRUNC = 8;
        MSG_DONTROUTE = 4;
        MSG_EOR = 128;
        MSG_OOB = 1;
        MSG_PEEK = 2;
        MSG_TRUNC = 32;
        MSG_WAITALL = 256;

        MS_ASYNC = 1;
        MS_INVALIDATE = 2;
        MS_SYNC = 4;

        NETLINK_NETFILTER = 12;
        NETLINK_ROUTE = 0;
        NETLINK_INET_DIAG = 4;
        NETLINK_XFRM = 6;

        NI_DGRAM = 16;

        O_ACCMODE = 3;
        O_APPEND = 1024;
        O_CLOEXEC = 524288;
        O_CREAT = 64;
        O_EXCL = 128;
        O_NOCTTY = 256;
        O_NONBLOCK = 2048;
        O_RDONLY = 0;
        O_RDWR = 2;
        O_SYNC = 1052672;
        O_DSYNC = 4096;
        O_TRUNC = 512;
        O_WRONLY = 1;

        POLLERR = 8;
        POLLHUP = 16;
        POLLIN = 1;
        POLLNVAL = 32;
        POLLOUT = 4;
        POLLPRI = 2;
        POLLRDBAND = 128;
        POLLRDNORM = 64;
        POLLWRBAND = 512;
        POLLWRNORM = 256;

        PR_CAP_AMBIENT = 47;
        PR_CAP_AMBIENT_RAISE = 2;
        PR_GET_DUMPABLE = 3;
        PR_SET_DUMPABLE = 4;
        PR_SET_NO_NEW_PRIVS = 38;

        PROT_EXEC = 4;
        PROT_NONE = 0;
        PROT_READ = 1;
        PROT_WRITE = 2;

        RLIMIT_NOFILE = 7;
        RLIMIT_RTPRIO = 14;

        RT_SCOPE_HOST = 254;
        RT_SCOPE_LINK = 253;
        RT_SCOPE_NOWHERE = 255;
        RT_SCOPE_SITE = 200;
        RT_SCOPE_UNIVERSE = 0;

        RTMGRP_IPV4_IFADDR = 16;
        RTMGRP_IPV4_MROUTE = 32;
        RTMGRP_IPV4_ROUTE = 64;
        RTMGRP_IPV4_RULE = 128;
        RTMGRP_IPV6_IFADDR = 256;
        RTMGRP_IPV6_IFINFO = 2048;
        RTMGRP_IPV6_MROUTE = 512;
        RTMGRP_IPV6_PREFIX = 131072;
        RTMGRP_IPV6_ROUTE = 1024;
        RTMGRP_LINK = 1;
        RTMGRP_NEIGH = 4;
        RTMGRP_NOTIFY = 2;
        RTMGRP_TC = 8;

        SEEK_CUR = 1;
        SEEK_END = 2;
        SEEK_SET = 0;

        SHUT_RD = 0;
        SHUT_RDWR = 2;
        SHUT_WR = 1;

        SIGABRT = 6;
        SIGALRM = 14;
        SIGBUS = 7;
        SIGCHLD = 17;
        SIGCONT = 18;
        SIGFPE = 8;
        SIGHUP = 1;
        SIGILL = 4;
        SIGINT = 2;
        SIGIO = 29;
        SIGKILL = 9;
        SIGPIPE = 13;
        SIGPROF = 27;
        SIGPWR = 30;
        SIGQUIT = 3;
        SIGRTMAX = 64;
        SIGSEGV = 11;
        SIGSTKFLT = 16;
        SIGSTOP = 19;
        SIGSYS = 31;
        SIGTERM = 15;
        SIGTRAP = 5;
        SIGTSTP = 20;
        SIGTTIN = 21;
        SIGTTOU = 22;
        SIGURG = 23;
        SIGUSR1 = 10;
        SIGUSR2 = 12;
        SIGVTALRM = 26;
        SIGWINCH = 28;
        SIGXCPU = 24;
        SIGXFSZ = 25;

        SIOCGIFADDR = 35093;
        SIOCGIFBRDADDR = 35097;
        SIOCGIFDSTADDR = 35095;
        SIOCGIFNETMASK = 35099;

        SOCK_CLOEXEC = 524288;
        SOCK_DGRAM = 2;
        SOCK_NONBLOCK = 2048;
        SOCK_RAW = 3;
        SOCK_SEQPACKET = 5;
        SOCK_STREAM = 1;

        SOL_SOCKET = 1;
        SOL_UDP = 17;
        SOL_PACKET = 263;

        SO_BINDTODEVICE = 25;
        SO_BROADCAST = 6;
        SO_DEBUG = 1;
        SO_DOMAIN = 39;
        SO_DONTROUTE = 5;
        SO_ERROR = 4;
        SO_KEEPALIVE = 9;
        SO_LINGER = 13;
        SO_OOBINLINE = 10;
        SO_PASSCRED = 16;
        SO_PEERCRED = 17;
        SO_PROTOCOL = 38;
        SO_RCVBUF = 8;
        SO_RCVLOWAT = 18;
        SO_RCVTIMEO = 20;
        SO_REUSEADDR = 2;
        SO_SNDBUF = 7;
        SO_SNDLOWAT = 19;
        SO_SNDTIMEO = 21;
        SO_TYPE = 3;

        PACKET_IGNORE_OUTGOING = 23;

        SPLICE_F_MOVE = 1;
        SPLICE_F_NONBLOCK = 2;
        SPLICE_F_MORE = 4;

        ST_MANDLOCK = 64;
        ST_NOATIME = 1024;
        ST_NODEV = 4;
        ST_NODIRATIME = 2048;
        ST_NOEXEC = 8;
        ST_NOSUID = 2;
        ST_RDONLY = 1;
        ST_RELATIME = 4096;
        ST_SYNCHRONOUS = 16;

        S_IFBLK = 24576;
        S_IFCHR = 8192;
        S_IFDIR = 16384;
        S_IFIFO = 4096;
        S_IFLNK = 40960;
        S_IFMT = 61440;
        S_IFREG = 32768;
        S_IFSOCK = 49152;
        S_IRGRP = 32;
        S_IROTH = 4;
        S_IRUSR = 256;
        S_IRWXG = 56;
        S_IRWXO = 7;
        S_IRWXU = 448;
        S_ISGID = 1024;
        S_ISUID = 2048;
        S_ISVTX = 512;
        S_IWGRP = 16;
        S_IWOTH = 2;
        S_IWUSR = 128;
        S_IXGRP = 8;
        S_IXOTH = 1;
        S_IXUSR = 64;

        TCP_NODELAY = 1;
        TCP_USER_TIMEOUT = 18;

        UDP_GRO = 104;
        UDP_SEGMENT = 103;

        TIOCOUTQ = 21521;

        UDP_ENCAP = 100;
        UDP_ENCAP_ESPINUDP_NON_IKE = 1;
        UDP_ENCAP_ESPINUDP = 2;

        // sizeof(sockaddr_un::sun_path).
        UNIX_PATH_MAX = 108;

        WCONTINUED = 8;
        WEXITED = 4;
        WNOHANG = 1;
        WNOWAIT = 16777216;
        WSTOPPED = 2;
        WUNTRACED = 2;
        W_OK = 2;

        XATTR_CREATE = 1;
        XATTR_REPLACE = 2;
        X_OK = 1;
    }
}
