#import "RNGeofencingLogger.h"

static BOOL RNGeofencingDebugEnabled = NO;

/// Bounded — this is a debugging aid, not an archive.
static const NSUInteger RNGeofencingRingCapacity = 300;
static NSMutableArray<NSString *> *RNGeofencingRing = nil;
static NSLock *RNGeofencingRingLock = nil;

__attribute__((constructor)) static void RNGeofencingRingInit(void) {
  RNGeofencingRing = [NSMutableArray arrayWithCapacity:RNGeofencingRingCapacity];
  RNGeofencingRingLock = [NSLock new];
}

@implementation RNGeofencingLogger

+ (BOOL)debugEnabled {
  return RNGeofencingDebugEnabled;
}

+ (void)setDebugEnabled:(BOOL)debugEnabled {
  RNGeofencingDebugEnabled = debugEnabled;
}

+ (void)log:(NSString *)level message:(NSString *)message {
  NSLog(@"[RNGeofencing] %@ %@", level, message);

  long long now = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
  [RNGeofencingRingLock lock];
  if (RNGeofencingRing.count >= RNGeofencingRingCapacity) {
    [RNGeofencingRing removeObjectAtIndex:0];
  }
  [RNGeofencingRing addObject:[NSString stringWithFormat:@"%lld %@ %@", now, level, message]];
  [RNGeofencingRingLock unlock];
}

+ (NSArray<NSString *> *)snapshot {
  [RNGeofencingRingLock lock];
  NSArray<NSString *> *copy = [RNGeofencingRing copy];
  [RNGeofencingRingLock unlock];
  return copy;
}

+ (void)clearSnapshot {
  [RNGeofencingRingLock lock];
  [RNGeofencingRing removeAllObjects];
  [RNGeofencingRingLock unlock];
}

+ (void)debug:(NSString *)format, ... {
  if (!RNGeofencingDebugEnabled) {
    return;
  }
  va_list args;
  va_start(args, format);
  NSString *message = [[NSString alloc] initWithFormat:format arguments:args];
  va_end(args);
  [self log:@"D" message:message];
}

+ (void)warn:(NSString *)format, ... {
  va_list args;
  va_start(args, format);
  NSString *message = [[NSString alloc] initWithFormat:format arguments:args];
  va_end(args);
  [self log:@"W" message:message];
}

+ (void)error:(NSString *)format, ... {
  va_list args;
  va_start(args, format);
  NSString *message = [[NSString alloc] initWithFormat:format arguments:args];
  va_end(args);
  [self log:@"E" message:message];
}

@end
