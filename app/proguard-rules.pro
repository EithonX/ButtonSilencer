# Shizuku loads the UserService class by its exact class name in a separate shell process.
-keep class com.buttons.silencer.PrivilegedMediaKeyService {
    public <init>();
    public <init>(android.content.Context);
    *;
}

-keep interface com.buttons.silencer.IPrivilegedBlocker { *; }
-keep class com.buttons.silencer.IPrivilegedBlocker$Stub { *; }
-keep class com.buttons.silencer.IPrivilegedBlocker$Stub$Proxy { *; }

# The hidden OnMediaKeyListener is reached reflectively in the Shizuku UserService.
-keepattributes InnerClasses,EnclosingMethod
