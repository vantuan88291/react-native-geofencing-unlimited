#import "RNGeofencingTypes.h"
#import "RNGeofencingLogger.h"

NSString *const RNGeofenceBoundaryID = @"@__rn_geofence_boundary__@";

static const double RNGeofenceEarthRadiusM = 6371008.8;

#pragma mark - Enum mapping

NSString *RNGeofenceActionToString(RNGeofenceAction action) {
  switch (action) {
    case RNGeofenceActionEnter:
      return @"ENTER";
    case RNGeofenceActionExit:
      return @"EXIT";
    case RNGeofenceActionDwell:
      return @"DWELL";
  }
  return @"ENTER";
}

BOOL RNGeofenceActionFromString(NSString *raw, RNGeofenceAction *out) {
  if ([raw isEqualToString:@"ENTER"]) {
    *out = RNGeofenceActionEnter;
    return YES;
  }
  if ([raw isEqualToString:@"EXIT"]) {
    *out = RNGeofenceActionExit;
    return YES;
  }
  if ([raw isEqualToString:@"DWELL"]) {
    *out = RNGeofenceActionDwell;
    return YES;
  }
  return NO;
}

NSString *RNGeofenceStateToString(RNGeofenceState state) {
  switch (state) {
    case RNGeofenceStateInside:
      return @"INSIDE";
    case RNGeofenceStateOutside:
      return @"OUTSIDE";
    case RNGeofenceStateUnknown:
      return @"UNKNOWN";
  }
  return @"UNKNOWN";
}

RNGeofenceState RNGeofenceStateFromString(NSString *raw) {
  if ([raw isEqualToString:@"INSIDE"]) {
    return RNGeofenceStateInside;
  }
  if ([raw isEqualToString:@"OUTSIDE"]) {
    return RNGeofenceStateOutside;
  }
  return RNGeofenceStateUnknown;
}

#pragma mark - Geometry

/**
 * The haversine form rather than the algebraically equivalent law-of-cosines form: it
 * stays accurate at the small distances this module actually deals in, where `acos`
 * of a value very close to 1 loses most of its precision.
 */
double RNGeofenceHaversine(CLLocationCoordinate2D from, CLLocationCoordinate2D to) {
  double lat1 = from.latitude * M_PI / 180.0;
  double lat2 = to.latitude * M_PI / 180.0;
  double dLat = lat2 - lat1;
  double dLng = (to.longitude - from.longitude) * M_PI / 180.0;

  double sinHalfDLat = sin(dLat / 2.0);
  double sinHalfDLng = sin(dLng / 2.0);
  double a = sinHalfDLat * sinHalfDLat + cos(lat1) * cos(lat2) * sinHalfDLng * sinHalfDLng;

  return 2.0 * RNGeofenceEarthRadiusM * asin(MIN(1.0, sqrt(a)));
}

double RNGeofenceClamp(double value, double low, double high) {
  // A degenerate range (low > high) resolves to `high`, which is the conservative
  // choice here: it is the proximity radius, never something larger.
  if (low > high) {
    return high;
  }
  if (value < low) {
    return low;
  }
  if (value > high) {
    return high;
  }
  return value;
}

#pragma mark - Helpers

static NSNumber *_Nullable RNNumberOrNil(id value) {
  return [value isKindOfClass:[NSNumber class]] ? (NSNumber *)value : nil;
}

static NSString *_Nullable RNStringOrNil(id value) {
  if (![value isKindOfClass:[NSString class]]) {
    return nil;
  }
  NSString *string = (NSString *)value;
  return string.length > 0 ? string : nil;
}

static BOOL RNBoolOrDefault(id value, BOOL fallback) {
  NSNumber *number = RNNumberOrNil(value);
  return number != nil ? number.boolValue : fallback;
}

static double RNDoubleOrDefault(id value, double fallback) {
  NSNumber *number = RNNumberOrNil(value);
  return number != nil ? number.doubleValue : fallback;
}

#pragma mark - RNGeofenceConfig

@implementation RNGeofenceConfig

+ (instancetype)defaultConfig {
  RNGeofenceConfig *config = [RNGeofenceConfig new];
  config.proximityRadius = 2000.0;
  config.initialTriggerEntry = YES;
  config.useSignificantLocationChanges = YES;
  config.minRadius = 200.0;
  config.debug = NO;
  config.notificationResponsiveness = 0;
  config.enableHeadless = NO;
  return config;
}

+ (instancetype)fromDictionary:(NSDictionary *)dictionary {
  RNGeofenceConfig *config = [RNGeofenceConfig defaultConfig];
  if (![dictionary isKindOfClass:[NSDictionary class]]) {
    return config;
  }
  config.proximityRadius = RNDoubleOrDefault(dictionary[@"proximityRadius"], config.proximityRadius);
  config.initialTriggerEntry =
      RNBoolOrDefault(dictionary[@"initialTriggerEntry"], config.initialTriggerEntry);
  config.useSignificantLocationChanges = RNBoolOrDefault(
      dictionary[@"useSignificantLocationChanges"], config.useSignificantLocationChanges);
  config.minRadius = RNDoubleOrDefault(dictionary[@"minRadius"], config.minRadius);
  config.debug = RNBoolOrDefault(dictionary[@"debug"], config.debug);
  config.notificationResponsiveness =
      (NSInteger)RNDoubleOrDefault(dictionary[@"notificationResponsiveness"], 0);
  config.enableHeadless = RNBoolOrDefault(dictionary[@"enableHeadless"], NO);
  return config;
}

- (NSDictionary *)toDictionary {
  return @{
    @"proximityRadius" : @(self.proximityRadius),
    @"initialTriggerEntry" : @(self.initialTriggerEntry),
    @"useSignificantLocationChanges" : @(self.useSignificantLocationChanges),
    @"minRadius" : @(self.minRadius),
    @"debug" : @(self.debug),
    @"notificationResponsiveness" : @(self.notificationResponsiveness),
    @"enableHeadless" : @(self.enableHeadless),
  };
}

- (id)copyWithZone:(NSZone *)zone {
  return [RNGeofenceConfig fromDictionary:[self toDictionary]];
}

@end

#pragma mark - RNGeofenceRecord

@implementation RNGeofenceRecord

- (instancetype)init {
  if (self = [super init]) {
    _notifyOnEntry = YES;
    _notifyOnExit = YES;
    _notifyOnDwell = NO;
    _loiteringDelay = 30000;
    _state = RNGeofenceStateUnknown;
    _dwellEmitted = NO;
    _active = NO;
  }
  return self;
}

- (CLLocationCoordinate2D)center {
  return CLLocationCoordinate2DMake(self.latitude, self.longitude);
}

+ (instancetype)fromSpec:(NSDictionary *)spec {
  RNGeofenceRecord *record = [RNGeofenceRecord new];
  record.identifier = RNStringOrNil(spec[@"identifier"]) ?: @"";
  record.latitude = RNDoubleOrDefault(spec[@"latitude"], NAN);
  record.longitude = RNDoubleOrDefault(spec[@"longitude"], NAN);
  record.radius = RNDoubleOrDefault(spec[@"radius"], 0.0);
  record.notifyOnEntry = RNBoolOrDefault(spec[@"notifyOnEntry"], YES);
  record.notifyOnExit = RNBoolOrDefault(spec[@"notifyOnExit"], YES);
  record.notifyOnDwell = RNBoolOrDefault(spec[@"notifyOnDwell"], NO);
  record.loiteringDelay = (NSInteger)RNDoubleOrDefault(spec[@"loiteringDelay"], 30000);
  // Already a JSON string on the wire (§8.1); stored verbatim and never parsed here.
  record.extras = RNStringOrNil(spec[@"extras"]);
  return record;
}

+ (instancetype)fromJSON:(NSDictionary *)json identifier:(NSString *)identifier {
  if (![json isKindOfClass:[NSDictionary class]]) {
    return nil;
  }
  RNGeofenceRecord *record = [RNGeofenceRecord new];
  record.identifier = identifier;
  record.latitude = RNDoubleOrDefault(json[@"lat"], 0.0);
  record.longitude = RNDoubleOrDefault(json[@"lng"], 0.0);
  record.radius = RNDoubleOrDefault(json[@"radius"], 0.0);
  record.notifyOnEntry = RNBoolOrDefault(json[@"onEntry"], YES);
  record.notifyOnExit = RNBoolOrDefault(json[@"onExit"], YES);
  record.notifyOnDwell = RNBoolOrDefault(json[@"onDwell"], NO);
  record.loiteringDelay = (NSInteger)RNDoubleOrDefault(json[@"loiteringDelay"], 30000);
  record.extras = RNStringOrNil(json[@"extras"]);
  record.state = RNGeofenceStateFromString(RNStringOrNil(json[@"state"]));
  record.enteredAt = RNNumberOrNil(json[@"enteredAt"]);
  record.dwellEmitted = RNBoolOrDefault(json[@"dwellEmitted"], NO);
  record.active = RNBoolOrDefault(json[@"active"], NO);
  return record;
}

- (NSDictionary *)toJSON {
  NSMutableDictionary *json = [@{
    @"lat" : @(self.latitude),
    @"lng" : @(self.longitude),
    @"radius" : @(self.radius),
    @"onEntry" : @(self.notifyOnEntry),
    @"onExit" : @(self.notifyOnExit),
    @"onDwell" : @(self.notifyOnDwell),
    @"loiteringDelay" : @(self.loiteringDelay),
    @"state" : RNGeofenceStateToString(self.state),
    @"dwellEmitted" : @(self.dwellEmitted),
    @"active" : @(self.active),
  } mutableCopy];
  if (self.extras != nil) {
    json[@"extras"] = self.extras;
  }
  if (self.enteredAt != nil) {
    json[@"enteredAt"] = self.enteredAt;
  }
  return json;
}

- (NSDictionary *)toSpec {
  NSMutableDictionary *spec = [@{
    @"identifier" : self.identifier,
    @"latitude" : @(self.latitude),
    @"longitude" : @(self.longitude),
    @"radius" : @(self.radius),
    @"notifyOnEntry" : @(self.notifyOnEntry),
    @"notifyOnExit" : @(self.notifyOnExit),
    @"notifyOnDwell" : @(self.notifyOnDwell),
    @"loiteringDelay" : @(self.loiteringDelay),
  } mutableCopy];
  if (self.extras != nil) {
    spec[@"extras"] = self.extras;
  }
  return spec;
}

- (BOOL)definitionChangedFrom:(RNGeofenceRecord *)other {
  return self.latitude != other.latitude || self.longitude != other.longitude ||
         self.radius != other.radius || self.notifyOnEntry != other.notifyOnEntry ||
         self.notifyOnExit != other.notifyOnExit || self.notifyOnDwell != other.notifyOnDwell ||
         self.loiteringDelay != other.loiteringDelay;
}

- (id)copyWithZone:(NSZone *)zone {
  RNGeofenceRecord *copy = [RNGeofenceRecord fromJSON:[self toJSON] identifier:self.identifier];
  return copy;
}

@end

#pragma mark - RNGeofenceEvent

@implementation RNGeofenceEvent

+ (instancetype)fromJSON:(NSDictionary *)json {
  if (![json isKindOfClass:[NSDictionary class]]) {
    return nil;
  }
  NSString *identifier = RNStringOrNil(json[@"id"]);
  RNGeofenceAction action;
  if (identifier == nil || !RNGeofenceActionFromString(RNStringOrNil(json[@"action"]), &action)) {
    return nil;
  }
  RNGeofenceEvent *event = [RNGeofenceEvent new];
  event.identifier = identifier;
  event.action = action;
  event.timestamp = (long long)RNDoubleOrDefault(json[@"ts"], 0);
  event.latitude = RNNumberOrNil(json[@"lat"]);
  event.longitude = RNNumberOrNil(json[@"lng"]);
  event.accuracy = RNNumberOrNil(json[@"accuracy"]);
  event.approximate = RNBoolOrDefault(json[@"approximate"], NO);
  event.synthetic = RNBoolOrDefault(json[@"synthetic"], NO);
  event.extras = RNStringOrNil(json[@"extras"]);
  return event;
}

- (NSDictionary *)toJSON {
  NSMutableDictionary *json = [@{
    @"id" : self.identifier,
    @"action" : RNGeofenceActionToString(self.action),
    @"ts" : @(self.timestamp),
    @"approximate" : @(self.approximate),
    @"synthetic" : @(self.synthetic),
  } mutableCopy];
  if (self.latitude != nil) {
    json[@"lat"] = self.latitude;
  }
  if (self.longitude != nil) {
    json[@"lng"] = self.longitude;
  }
  if (self.accuracy != nil) {
    json[@"accuracy"] = self.accuracy;
  }
  if (self.extras != nil) {
    json[@"extras"] = self.extras;
  }
  return json;
}

- (NSDictionary *)toPayload {
  NSMutableDictionary *payload = [@{
    @"identifier" : self.identifier,
    @"action" : RNGeofenceActionToString(self.action),
    @"timestamp" : @(self.timestamp),
    @"approximate" : @(self.approximate),
    @"synthetic" : @(self.synthetic),
  } mutableCopy];
  payload[@"latitude"] = self.latitude ?: [NSNull null];
  payload[@"longitude"] = self.longitude ?: [NSNull null];
  payload[@"accuracy"] = self.accuracy ?: [NSNull null];
  payload[@"extras"] = self.extras ?: [NSNull null];
  return payload;
}

@end

#pragma mark - RNGeofenceMeta

@implementation RNGeofenceMeta

+ (instancetype)fromDictionary:(NSDictionary *)dictionary {
  RNGeofenceMeta *meta = [RNGeofenceMeta new];
  if (![dictionary isKindOfClass:[NSDictionary class]]) {
    return meta;
  }
  meta.centerLatitude = RNNumberOrNil(dictionary[@"centerLat"]);
  meta.centerLongitude = RNNumberOrNil(dictionary[@"centerLng"]);
  meta.centerAt = RNNumberOrNil(dictionary[@"centerAt"]);
  meta.boundaryRadius = RNNumberOrNil(dictionary[@"boundaryRadius"]);
  meta.enabled = RNBoolOrDefault(dictionary[@"enabled"], NO);
  meta.droppedCount = (NSInteger)RNDoubleOrDefault(dictionary[@"droppedCount"], 0);
  return meta;
}

- (NSDictionary *)toDictionary {
  NSMutableDictionary *dictionary = [@{
    @"enabled" : @(self.enabled),
    @"droppedCount" : @(self.droppedCount),
  } mutableCopy];
  if (self.centerLatitude != nil) {
    dictionary[@"centerLat"] = self.centerLatitude;
  }
  if (self.centerLongitude != nil) {
    dictionary[@"centerLng"] = self.centerLongitude;
  }
  if (self.centerAt != nil) {
    dictionary[@"centerAt"] = self.centerAt;
  }
  if (self.boundaryRadius != nil) {
    dictionary[@"boundaryRadius"] = self.boundaryRadius;
  }
  return dictionary;
}

- (BOOL)hasCenter {
  return self.centerLatitude != nil && self.centerLongitude != nil;
}

- (CLLocationCoordinate2D)center {
  return CLLocationCoordinate2DMake(self.centerLatitude.doubleValue,
                                    self.centerLongitude.doubleValue);
}

- (id)copyWithZone:(NSZone *)zone {
  return [RNGeofenceMeta fromDictionary:[self toDictionary]];
}

@end
