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
/**
 * Recent lines, for `getDebugLog()`.
 *
 * The most interesting logging this module does happens during a process wake — the
 * launch re-arm, or a region callback in a background relaunch — which is *before* JS
 * is running and therefore impossible to observe from a JS logger. Buffering natively
 * and letting JS pull the lines later is what makes those visible without attaching a
 * native log viewer. Reading does not drain, so repeated calls are safe.
 */
+ (NSArray<NSString *> *)snapshot;
+ (void)clearSnapshot;

+ (void)debug:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (void)warn:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
+ (void)error:(NSString *)format, ... NS_FORMAT_FUNCTION(1, 2);
@end

NS_ASSUME_NONNULL_END
