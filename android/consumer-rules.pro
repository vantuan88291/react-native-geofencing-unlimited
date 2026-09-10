# Shipped with the AAR and applied to every consuming app's R8 run.
#
# Two of these components are addressed at runtime by **fully-qualified name**, not by
# class literal, so that `core/` keeps no compile-time edge into the React Native layer
# (§11, invariant 1):
#
#   PlatformRegistry -> ComponentName(pkg, "com.rngeofencing.GeofenceBroadcastReceiver")
#   Core             -> ComponentName(pkg, "com.rngeofencing.GeofenceHeadlessService")
#
# AGP does generate keep rules from the merged manifest, so in practice these survive
# already — but that is a property of the manifest merge, not of this library, and the
# cost of being wrong is the worst failure this module has: rename either class and the
# PendingIntent points at a component that does not exist, so geofences stop being
# delivered **in release builds only**, with no crash and no log.
-keep class com.rngeofencing.GeofenceBroadcastReceiver { *; }
-keep class com.rngeofencing.BootReceiver { *; }
-keep class com.rngeofencing.GeofenceHeadlessService { *; }

# Instantiated by autolinking-generated code.
-keep class com.rngeofencing.GeofencingPackage { *; }
