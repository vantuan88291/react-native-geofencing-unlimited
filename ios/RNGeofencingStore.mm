#import "RNGeofencingStore.h"
#import "RNGeofencingLogger.h"

static NSString *const RNGeofenceSuiteName = @"rn_geofencing";
static NSString *const RNGeofenceKeyGeofences = @"geofences";
static NSString *const RNGeofenceKeyEvents = @"events";
static NSString *const RNGeofenceKeyMeta = @"meta";
static NSString *const RNGeofenceKeyConfig = @"config";
static const NSInteger RNGeofenceBlobVersion = 1;

/**
 * `UserDefaults`-backed store (§9).
 *
 * The one rule that makes this safe: **one JSON blob per concern, under one key**
 * (§9.1, invariant 7). `UserDefaults` rewrites its whole plist atomically (write to
 * temp, then rename), so a single key gives the same all-or-nothing guarantee a
 * SQLite transaction would. One key *per geofence* would throw that away — a process
 * killed mid-write would leave a half-updated set, which is precisely the "geofences
 * silently stopped working" failure the design exists to avoid.
 *
 * The equivalent of Android's Direct Boot caveat here is file data protection: if the
 * host app raises the protection class to `NSFileProtectionComplete`, these reads can
 * fail while the device is locked. The default
 * (`…CompleteUntilFirstUserAuthentication`) is fine, and the README flags it rather
 * than the module designing around it (§9.7).
 */
@implementation RNGeofencingStore {
  NSUserDefaults *_defaults;
  /// The in-memory working copy. All reads come from here, at zero I/O cost (§9.3).
  NSMutableDictionary<NSString *, RNGeofenceRecord *> *_records;
  /// Insertion order, so the queue and the set are stable across a reload.
  NSMutableArray<NSString *> *_order;
  NSMutableArray<RNGeofenceEvent *> *_queue;
  BOOL _loaded;
  /**
   * Set when a blob carried a `"v"` this build does not understand. While it is set,
   * that blob is never rewritten from memory — the user's registered set is preserved
   * for a future migration instead of being silently overwritten (§9.2).
   */
  BOOL _geofencesQuarantined;
}

- (instancetype)init {
  if (self = [super init]) {
    _defaults = [[NSUserDefaults alloc] initWithSuiteName:RNGeofenceSuiteName];
    _records = [NSMutableDictionary new];
    _order = [NSMutableArray new];
    _queue = [NSMutableArray new];
    _meta = [RNGeofenceMeta new];
    _config = [RNGeofenceConfig defaultConfig];
    _loaded = NO;
    _geofencesQuarantined = NO;
  }
  return self;
}

#pragma mark - Load

- (void)load {
  if (_loaded) {
    return;
  }
  _loaded = YES;

  [self readGeofences];
  [self readEvents];
  [self readMeta];
  [self readConfig];

  RNGeofencingLogger.debugEnabled = self.config.debug;
  [RNGeofencingLogger debug:@"store loaded: %ld geofences, %ld queued events, enabled=%@",
                            (long)_records.count, (long)_queue.count,
                            self.meta.enabled ? @"YES" : @"NO"];
}

- (void)readGeofences {
  NSDictionary *root = [self readBlob:RNGeofenceKeyGeofences];
  if (root == nil) {
    return;
  }
  if (![self checkVersion:root key:RNGeofenceKeyGeofences]) {
    _geofencesQuarantined = YES;
    return;
  }
  NSDictionary *items = root[@"items"];
  if (![items isKindOfClass:[NSDictionary class]]) {
    return;
  }
  for (NSString *identifier in items) {
    RNGeofenceRecord *record = [RNGeofenceRecord fromJSON:items[identifier]
                                               identifier:identifier];
    if (record != nil) {
      _records[identifier] = record;
      [_order addObject:identifier];
    }
  }
}

- (void)readEvents {
  NSDictionary *root = [self readBlob:RNGeofenceKeyEvents];
  if (root == nil || ![self checkVersion:root key:RNGeofenceKeyEvents]) {
    return;
  }
  NSArray *items = root[@"items"];
  if (![items isKindOfClass:[NSArray class]]) {
    return;
  }
  for (id item in items) {
    RNGeofenceEvent *event = [RNGeofenceEvent fromJSON:item];
    if (event != nil) {
      [_queue addObject:event];
    }
  }
}

- (void)readMeta {
  NSDictionary *root = [self readBlob:RNGeofenceKeyMeta];
  if (root == nil || ![self checkVersion:root key:RNGeofenceKeyMeta]) {
    return;
  }
  _meta = [RNGeofenceMeta fromDictionary:root];
}

- (void)readConfig {
  NSDictionary *root = [self readBlob:RNGeofenceKeyConfig];
  if (root == nil || ![self checkVersion:root key:RNGeofenceKeyConfig]) {
    return;
  }
  _config = [RNGeofenceConfig fromDictionary:root];
}

- (nullable NSDictionary *)readBlob:(NSString *)key {
  NSString *raw = [_defaults stringForKey:key];
  if (raw.length == 0) {
    return nil;
  }
  NSError *error = nil;
  id parsed = [NSJSONSerialization JSONObjectWithData:[raw dataUsingEncoding:NSUTF8StringEncoding]
                                              options:0
                                                error:&error];
  if (![parsed isKindOfClass:[NSDictionary class]]) {
    // Never throw out of a launch path on a corrupt blob (§9.2). Starting empty loses
    // the set; crashing loses the set *and* every future crossing.
    [RNGeofencingLogger error:@"blob '%@' unreadable (%@), starting empty", key,
                              error.localizedDescription ?: @"not an object"];
    [self quarantine:key raw:raw reason:@"corrupt"];
    return nil;
  }
  return (NSDictionary *)parsed;
}

/**
 * An unknown `"v"` must neither crash nor be overwritten (§9.2). The raw string is
 * copied aside so a later migration still has it, and this build carries on empty.
 */
- (BOOL)checkVersion:(NSDictionary *)root key:(NSString *)key {
  NSNumber *version = root[@"v"];
  if ([version isKindOfClass:[NSNumber class]] && version.integerValue == RNGeofenceBlobVersion) {
    return YES;
  }
  [RNGeofencingLogger error:@"blob '%@' has version %@, this build understands %ld — keeping the "
                            @"raw value aside and starting empty",
                            key, version ?: @"(none)", (long)RNGeofenceBlobVersion];
  [self quarantine:key
               raw:[_defaults stringForKey:key]
            reason:[NSString stringWithFormat:@"v%@", version ?: @"unknown"]];
  return NO;
}

- (void)quarantine:(NSString *)key raw:(nullable NSString *)raw reason:(NSString *)reason {
  if (raw == nil) {
    return;
  }
  NSString *backupKey = [NSString stringWithFormat:@"%@.%@.backup", key, reason];
  if ([_defaults objectForKey:backupKey] != nil) {
    return;  // already preserved; do not overwrite it
  }
  [_defaults setObject:raw forKey:backupKey];
}

#pragma mark - Reads (memory only)

- (NSArray<RNGeofenceRecord *> *)all {
  NSMutableArray<RNGeofenceRecord *> *result = [NSMutableArray arrayWithCapacity:_records.count];
  for (NSString *identifier in _order) {
    RNGeofenceRecord *record = _records[identifier];
    if (record != nil) {
      [result addObject:record];
    }
  }
  return result;
}

- (nullable RNGeofenceRecord *)get:(NSString *)identifier {
  return _records[identifier];
}

- (NSInteger)count {
  return (NSInteger)_records.count;
}

- (NSSet<NSString *> *)activeIdentifiers {
  NSMutableSet<NSString *> *active = [NSMutableSet new];
  for (RNGeofenceRecord *record in [self all]) {
    if (record.active) {
      [active addObject:record.identifier];
    }
  }
  return active;
}

- (NSInteger)queueSize {
  return (NSInteger)_queue.count;
}

#pragma mark - Mutations (memory, then write-through — never write-behind, §9.3)

- (void)upsert:(NSArray<RNGeofenceRecord *> *)records {
  if (records.count == 0) {
    return;
  }
  for (RNGeofenceRecord *record in records) {
    RNGeofenceRecord *existing = _records[record.identifier];
    if (existing == nil) {
      [_order addObject:record.identifier];
    } else {
      // An upsert of an existing geofence keeps its gate state: re-registering the
      // same circle is not a reason to forget the device is already inside it (§5.1).
      record.state = existing.state;
      record.enteredAt = existing.enteredAt;
      record.dwellEmitted = existing.dwellEmitted;
      record.active = existing.active && ![record definitionChangedFrom:existing];
    }
    _records[record.identifier] = record;
  }
  [self writeGeofences];
}

- (void)deleteIdentifiers:(NSArray<NSString *> *)identifiers {
  if (identifiers.count == 0) {
    return;
  }
  BOOL changed = NO;
  for (NSString *identifier in identifiers) {
    if (_records[identifier] != nil) {
      [_records removeObjectForKey:identifier];
      [_order removeObject:identifier];
      changed = YES;
    }
  }
  if (changed) {
    [self writeGeofences];
  }
}

- (void)updateState:(NSString *)identifier
              state:(RNGeofenceState)state
          enteredAt:(nullable NSNumber *)enteredAt
       dwellEmitted:(BOOL)dwellEmitted {
  RNGeofenceRecord *record = _records[identifier];
  if (record == nil) {
    return;
  }
  record.state = state;
  record.enteredAt = enteredAt;
  record.dwellEmitted = dwellEmitted;
  [self writeGeofences];
}

- (void)setActiveIdentifiers:(NSSet<NSString *> *)identifiers {
  BOOL changed = NO;
  for (RNGeofenceRecord *record in [self all]) {
    BOOL next = [identifiers containsObject:record.identifier];
    if (record.active != next) {
      record.active = next;
      changed = YES;
    }
  }
  if (changed) {
    [self writeGeofences];
  }
}

- (void)setActive:(NSArray<NSString *> *)identifiers active:(BOOL)active {
  BOOL changed = NO;
  for (NSString *identifier in identifiers) {
    RNGeofenceRecord *record = _records[identifier];
    if (record != nil && record.active != active) {
      record.active = active;
      changed = YES;
    }
  }
  if (changed) {
    [self writeGeofences];
  }
}

- (void)enqueue:(NSArray<RNGeofenceEvent *> *)events {
  if (events.count == 0) {
    return;
  }
  [_queue addObjectsFromArray:events];
  NSInteger dropped = 0;
  while ((NSInteger)_queue.count > RNGeofenceMaxQueuedEvents) {
    [_queue removeObjectAtIndex:0];  // FIFO: oldest first (§9.5)
    dropped++;
  }
  if (dropped > 0) {
    [RNGeofencingLogger warn:@"event queue full, dropped %ld oldest event(s)", (long)dropped];
    self.meta.droppedCount += dropped;
    [self writeMeta];
  }
  [self writeEvents];
}

- (NSArray<RNGeofenceEvent *> *)drainQueue {
  if (_queue.count == 0) {
    return @[];
  }
  NSArray<RNGeofenceEvent *> *drained = [_queue copy];
  [_queue removeAllObjects];
  [self writeEvents];
  return drained;
}

#pragma mark - Meta / config

- (void)setMeta:(RNGeofenceMeta *)meta {
  _meta = meta;
  [self writeMeta];
}

- (void)setConfig:(RNGeofenceConfig *)config {
  _config = config;
  RNGeofencingLogger.debugEnabled = config.debug;
  [self writeConfig];
}

#pragma mark - Serialisation

- (void)writeGeofences {
  if (_geofencesQuarantined) {
    [RNGeofencingLogger warn:@"refusing to overwrite a quarantined geofences blob; changes are in "
                             @"memory only"];
    return;
  }
  NSMutableDictionary *items = [NSMutableDictionary dictionaryWithCapacity:_records.count];
  for (RNGeofenceRecord *record in [self all]) {
    items[record.identifier] = [record toJSON];
  }
  [self writeBlob:RNGeofenceKeyGeofences
             root:@{@"v" : @(RNGeofenceBlobVersion), @"items" : items}];
}

- (void)writeEvents {
  NSMutableArray *items = [NSMutableArray arrayWithCapacity:_queue.count];
  for (RNGeofenceEvent *event in _queue) {
    [items addObject:[event toJSON]];
  }
  [self writeBlob:RNGeofenceKeyEvents root:@{@"v" : @(RNGeofenceBlobVersion), @"items" : items}];
}

- (void)writeMeta {
  NSMutableDictionary *root = [[self.meta toDictionary] mutableCopy];
  root[@"v"] = @(RNGeofenceBlobVersion);
  [self writeBlob:RNGeofenceKeyMeta root:root];
}

- (void)writeConfig {
  NSMutableDictionary *root = [[self.config toDictionary] mutableCopy];
  root[@"v"] = @(RNGeofenceBlobVersion);
  [self writeBlob:RNGeofenceKeyConfig root:root];
}

- (void)writeBlob:(NSString *)key root:(NSDictionary *)root {
  NSError *error = nil;
  NSData *data = [NSJSONSerialization dataWithJSONObject:root options:0 error:&error];
  if (data == nil) {
    [RNGeofencingLogger error:@"failed to serialise '%@': %@", key, error.localizedDescription];
    return;
  }
  [_defaults setObject:[[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding]
                forKey:key];
}

- (void)synchronizeNow {
  [_defaults synchronize];
}

@end
