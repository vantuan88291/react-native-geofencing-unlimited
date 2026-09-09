require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "RNGeofencing"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :git => "https://github.com/vantuan88291/react-native-geofencing-unlimited.git", :tag => "#{s.version}" }

  # No `swift` in the glob, and that is deliberate (§11). The iOS side is ObjC++
  # throughout: a `.swift` file placed here would be silently not compiled, and adding
  # Swift would mean a second language in a kotlin-objc package plus a bridging story
  # for the ObjC++ TurboModule surface.
  #
  # It is also why the §7.5 launch hook is mechanism C of §18.3 rather than
  # `ExpoAppDelegateSubscriber`: that protocol is Swift-only and would drag
  # `expo-modules-core` onto every plain React Native CLI consumer.
  s.source_files = "ios/**/*.{h,m,mm,cpp}"

  # RNGeofencingCore is public so a host app that wants to construct the core from its
  # own AppDelegate can still do so. It does not need to — `+load` handles it — but a
  # header nobody can import forecloses the option.
  s.public_header_files = "ios/RNGeofencing.h", "ios/RNGeofencingCore.h", "ios/RNGeofencingTypes.h"

  # The only framework this module adds. Everything else it touches (UIApplication,
  # for beginBackgroundTask in the §7.4 dwell emulation) is UIKit, already linked.
  s.frameworks = "CoreLocation"

  # RN 0.71+ wires up both architectures, including the RCT_NEW_ARCH_ENABLED define
  # that ios/RNGeofencing.h branches on.
  if respond_to?(:install_modules_dependencies, true)
    install_modules_dependencies(s)
  else
    s.dependency "React-Core"
  end
end
