# Copyright 2026 The Wayfinder Authors
# SPDX-License-Identifier: Apache-2.0

# Optional JDK / Java EE classes referenced by smbj (Kerberos SPNEGO), dcerpc (Java RMI),
# and mbassador (Jakarta EL filters) that do not exist in Android's bootclasspath.
# Wayfinder uses NtlmAuthenticator on Android and never invokes SPNEGO or EL message filters.
-dontwarn org.ietf.jgss.**
-dontwarn java.rmi.**
-dontwarn javax.el.**
-dontwarn javax.activation.**

# Preserve source file names and line numbers for readable crash stack traces in release builds.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# MBassador dispatches SMBJ connection and session lifecycle events via runtime @Handler reflection.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class ** {
    @net.engio.mbassy.listener.Handler <methods>;
}

# SMBJ, ASN.1, DCERPC, and Bouncy Castle JCA crypto providers instantiated reflectively for SMB2/3.
-keep class com.hierynomus.** { *; }
-keep class com.rapid7.client.dcerpc.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
