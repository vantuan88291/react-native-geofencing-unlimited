// Autolinking can normally infer the Android package from a class called
// `GeofencingPackage`, but this module's failure mode when it does not is silence —
// no crash, no log, just no geofences — so it is stated explicitly (§11).
module.exports = {
  dependency: {
    platforms: {
      android: {
        packageImportPath: 'import com.rngeofencing.GeofencingPackage;',
        packageInstance: 'new GeofencingPackage()',
      },
    },
  },
};
