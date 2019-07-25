<h1 align="center">Android原理知识点</h1>

* 一个apk从开始安装到启动，系统都做了哪些事情？请从AMS，WMS，PMS的角度考虑下，以及进程是如何启动的？
* AMS和WMS相关的数据结构和沟通桥梁是什么？AMS的堆栈是如何管理的？WMS的堆栈是如何管理的？
* PMS相关的开机流程，apk安装流程，adb install和pms scan的区别有哪些？
* Broadcast的机制，分发流程是什么？如何传递到每个app进程的？动态广播和静态广播的处理流程在哪里不一样？
* 多用户最主要的机制以及创建一个新的用户系统需要做哪些事情。
* Runtime permission，如何把一个预置的app默认给它权限，不需要授权。
* 如何实现预装的apk在卸载后，通过恢复出厂设置恢复过来，请介绍下方案。
* Android资源加载和打包的机制介绍，一个图片在app中调用R.id调用后是如何找到的？
* Android Overlay的机制是什么？
* Android的权限管理的机制是什么？
* 为何android.uid.system相关的进程不能访问sdcard
* 开机流程和关机流程描述下。
* Bootainmation是如何启动和退出的。
* Binder相关的机制以及在Android平台的使用，Android还有什么IPC通信方式，各有什么优缺点？
* 死机、重启等stability问题分析流程？watchdog reset如何分析？
* Native Crash问题如何分析，以及crash在art相关的oat，odex文件如何分析
* Android ART机制，与dalvik的区别，JIT与AOT的区别是什么？ART GC有什么改善，还有什么缺点？
* ANR，OOM等问题的分析流程介绍
* Android ++ 智能指针相关的使用介绍
* Android编译、优化、ART相关编译优化
* Input相关事件的分发机制，tp相关的问题解决
* 按键事件和tp事件的处理有什么不同点和相同点吗？
* 功耗相关问题的分析
* 性能相关问题的分析
* Android N与M的一些典型的改变有哪些？Multi-window机制介绍
* PowerManagerService主要做了哪些相关的操作？系统亮灭屏都有哪些流程？
* Wakelock机制，android如何和Linux管理这些Wakelock
* Alarm相关机制，doze相关的机制以及运行方式。