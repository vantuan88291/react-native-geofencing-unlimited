#import "RNGeofencingLogger.h"

static BOOL RNGeofencingDebugEnabled = NO;

@implementation RNGeofencingLogger

+ (BOOL)debugEnabled {
  return RNGeofencingDebugEnabled;
}

+ (void)setDebugEnabled:(BOOL)debugEnabled {
  RNGeofencingDebugEnabled = debugEnabled;
}

+ (void)log:(NSString *)level message:(NSString *)message {
  NSLog(@"[RNGeofencing] %@ %@", level, message);
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
