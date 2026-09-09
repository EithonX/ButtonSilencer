typedef int jint;
typedef unsigned char jboolean;
typedef void JNIEnv;
typedef void* jclass;
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

/* _IOW('E', 0x90, int) from linux/input.h. */
#define EVIOCGRAB_REQUEST 0x40044590UL

/*
 * EVIOCGRAB is unusual: evdev interprets the ioctl's third argument itself as a
 * boolean pointer value. A non-NULL value grabs; NULL releases. It does NOT
 * dereference an int for this command. Passing &arg would therefore make both
 * grab and release non-NULL and could never perform a normal ungrab.
 */
static long raw_ioctl(int fd, unsigned long request, unsigned long arg) {
#if defined(__aarch64__)
    register long x0 __asm__("x0") = fd;
    register long x1 __asm__("x1") = (long) request;
    register long x2 __asm__("x2") = (long) arg;
    register long x8 __asm__("x8") = 29;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x8) : "memory");
    return x0;
#elif defined(__arm__)
    register long r0 __asm__("r0") = fd;
    register long r1 __asm__("r1") = (long) request;
    register long r2 __asm__("r2") = (long) arg;
    register long r7 __asm__("r7") = 54;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return r0;
#elif defined(__x86_64__)
    register long rax __asm__("rax") = 16;
    register long rdi __asm__("rdi") = fd;
    register long rsi __asm__("rsi") = (long) request;
    register long rdx __asm__("rdx") = (long) arg;
    __asm__ volatile("syscall" : "+a"(rax) : "D"(rdi), "S"(rsi), "d"(rdx) : "rcx", "r11", "memory");
    return rax;
#elif defined(__i386__)
    register long eax __asm__("eax") = 54;
    register long ebx __asm__("ebx") = fd;
    register long ecx __asm__("ecx") = (long) request;
    register long edx __asm__("edx") = (long) arg;
    __asm__ volatile("int $0x80" : "+a"(eax) : "b"(ebx), "c"(ecx), "d"(edx) : "memory");
    return eax;
#else
#error Unsupported architecture
#endif
}

JNIEXPORT jint JNICALL
Java_com_buttons_silencer_EvdevExclusiveGuard_nativeSetGrab(
        JNIEnv *env,
        jclass ignored,
        jint fd,
        jboolean enabled) {
    (void) env;
    (void) ignored;
    if (fd < 0) {
        return 9;
    }

    /* See evdev.c: case EVIOCGRAB: if (p) grab; else ungrab. */
    unsigned long arg = enabled ? 1UL : 0UL;
    long rc = raw_ioctl(fd, EVIOCGRAB_REQUEST, arg);
    if (rc < 0 && rc >= -4095) {
        return (jint) -rc;
    }
    return 0;
}
