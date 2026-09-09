#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * `debug: true` logging (§14).
 *
 * Almost every field report about this module resolves to reading a rotation log, so
 * the rotation logger prints the centre, the boundary radius and the on/off diff —
 * not just "rotated". Warnings and errors are always logged; only the verbose
 * rotation trace is gated on the flag.
 */
@interface RNGeofencingLogger : NSObject
@property(class, nonatomic, assign) BOOL debugEnabled;
+ (void)debug:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (void)warn:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (void)error:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
@end

NS_ASSUME_NONNULL_END
