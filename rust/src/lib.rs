//! JNI bridge from LightweightBrowser to Brave's adblock-rust engine.
//!
//! Phase 1 scope: network decisions only. Cosmetic filtering and media JS stay
//! in Kotlin for now, and the Kotlin engine remains the default.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::RwLock;

static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);
static FILTER_BYTES: AtomicUsize = AtomicUsize::new(0);

/// High-signal tracker hosts already blocked by the Kotlin fallback. They are
/// installed explicitly so the two engines agree even when upstream lists only
/// carry narrower rules for a host.
const REQUIRED_BLOCKS: &str = "
||doubleclick.net^
||googleadservices.com^
||googlesyndication.com^
||google-analytics.com^
||googletagservices.com^
||adservice.google.com^
||adnxs.com^
||adsrvr.org^
||rubiconproject.com^
||pubmatic.com^
||criteo.com^
||casalemedia.com^
||openx.net^
||yieldmo.com^
||3lift.com^
||adcolony.com^
||applovin.com^
||unityads.com^
||vungle.com^
||scorecardresearch.com^
||quantserve.com^
||hotjar.com^
||mixpanel.com^
||amplitude.com^
||fullstory.com^
||taboola.com^
||outbrain.com^
||bat.bing.com^
||clarity.ms^
||newrelic.com^
||connect.facebook.net^
||platform.twitter.com^
||amazon-adsystem.com^
! Observed rotating gambling/ad affiliates on watchseries.bar.
||hdaujcw.online^
||krikya11.me^
||sharky777.xyz^
||stalkoda.com^
||llvpn.com^
! YouTube ad endpoints only; player/video hosts stay allowed.
||googleads.g.doubleclick.net^$domain=youtube.com
||pagead2.googlesyndication.com^$domain=youtube.com
||googlesyndication.com^$domain=youtube.com
||www.youtube.com/ads^
||youtube.com/api/stats/ads
||youtube.com/ads/
";

/// Asset hosts needed by video players and sign-in. Only known subdomains are
/// exempted: a blanket gstatic/googleusercontent exception would hide genuine
/// tracker rules. Document exceptions protect navigation to YouTube itself
/// without disabling subresource filtering on YouTube pages.
const REQUIRED_EXCEPTIONS: &str = "
@@||googlevideo.com^
@@||ytimg.com^
@@||ggpht.com^
@@||www.gstatic.com^
@@||fonts.gstatic.com^
@@||ssl.gstatic.com^
@@||lh3.googleusercontent.com^
@@||lh4.googleusercontent.com^
@@||lh5.googleusercontent.com^
@@||lh6.googleusercontent.com^
@@||yt3.googleusercontent.com^
@@||youtube.com^$document
@@||youtu.be^$document
@@||cdn-media.brightline.tv^
";

/// Consumes the caller's filter text so the multi-megabyte list is not copied
/// a second time before being parsed.
fn build_engine(filters: String) -> Engine {
    let mut filter_set = FilterSet::new(false);
    filter_set.add_filter_list(REQUIRED_BLOCKS.to_string(), ParseOptions::default());
    filter_set.add_filter_list(REQUIRED_EXCEPTIONS.to_string(), ParseOptions::default());
    filter_set.add_filter_list(filters, ParseOptions::default());
    Engine::new_with_filter_set(filter_set)
}

fn should_block(engine: &Engine, url: &str, page_url: &str, resource_type: &str, method: &str) -> bool {
    let request = match Request::new(url, page_url, resource_type, method) {
        Ok(request) => request,
        Err(_) => return false,
    };
    engine.check_network_request(&request).should_block()
}

fn get_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    let java_str = env.get_string(value).ok()?;
    Some(java_str.into())
}

/// JNI must never let a Rust panic unwind into the JVM. A failed decision is
/// reported as "not blocked" so the page keeps loading instead of the app dying.
fn catch_bool<F: FnOnce() -> jboolean>(body: F) -> jboolean {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(JNI_FALSE)
}

fn catch_string<F: FnOnce() -> jstring>(body: F) -> jstring {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_lightweightbrowser_RustAdBlocker_nativeInit(
    mut env: JNIEnv,
    _this: JObject,
    filters: JString,
) -> jboolean {
    catch_bool(move || native_init(&mut env, _this, filters))
}

fn native_init(env: &mut JNIEnv, _this: JObject, filters: JString) -> jboolean {
    let Some(filters) = get_string(env, &filters) else {
        return JNI_FALSE;
    };
    if filters.is_empty() {
        return JNI_FALSE;
    }

    let filter_bytes = filters.len();
    let engine = build_engine(filters);
    match ENGINE.write() {
        Ok(mut guard) => {
            FILTER_BYTES.store(filter_bytes, Ordering::Relaxed);
            *guard = Some(engine);
            JNI_TRUE
        }
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lightweightbrowser_RustAdBlocker_nativeShouldBlock(
    mut env: JNIEnv,
    _this: JObject,
    url: JString,
    page_url: JString,
    resource_type: JString,
    method: JString,
) -> jboolean {
    catch_bool(move || {
        native_should_block(
            &mut env,
            _this,
            url,
            page_url,
            resource_type,
            method,
        )
    })
}

fn native_should_block(
    env: &mut JNIEnv,
    _this: JObject,
    url: JString,
    page_url: JString,
    resource_type: JString,
    method: JString,
) -> jboolean {
    let (Some(url), Some(page_url), Some(resource_type), Some(method)) = (
        get_string(env, &url),
        get_string(env, &page_url),
        get_string(env, &resource_type),
        get_string(env, &method),
    ) else {
        return JNI_FALSE;
    };

    let guard = match ENGINE.read() {
        Ok(guard) => guard,
        Err(_) => return JNI_FALSE,
    };
    let Some(engine) = guard.as_ref() else {
        return JNI_FALSE;
    };

    if should_block(engine, &url, &page_url, &resource_type, &method) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lightweightbrowser_RustAdBlocker_nativeStats(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    catch_string(move || native_stats(&mut env, _this))
}

fn native_stats(env: &mut JNIEnv, _this: JObject) -> jstring {
    let initialized = ENGINE.read().map(|guard| guard.is_some()).unwrap_or(false);
    let bytes = FILTER_BYTES.load(Ordering::Relaxed);
    let json = format!("{{\"initialized\":{},\"filterBytes\":{}}}", initialized, bytes);
    match env.new_string(json) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    struct Case {
        url: &'static str,
        page_url: &'static str,
        resource_type: &'static str,
        expected: bool,
    }

    fn cases() -> Vec<Case> {
        include_str!("../../app/src/test/resources/blocker_parity_cases.tsv")
            .lines()
            .filter(|line| !line.trim().is_empty() && !line.starts_with('#'))
            .map(|line| {
                let parts: Vec<&str> = line.split('\t').collect();
                assert_eq!(parts.len(), 4, "bad fixture line: {line}");
                Case {
                    url: parts[0],
                    page_url: parts[1],
                    resource_type: parts[2],
                    expected: parts[3] == "1",
                }
            })
            .collect()
    }

    #[test]
    fn panics_in_jni_body_are_caught() {
        assert_eq!(JNI_FALSE, catch_bool(|| panic!("simulated JNI panic")));
        assert!(catch_string(|| panic!("simulated JNI panic")).is_null());
    }

    #[test]
    fn builds_with_bundled_filters() {
        let filters = include_str!("../../app/src/main/assets/adblock_filters.txt");
        let started = Instant::now();
        let engine = build_engine(filters.to_string());
        let elapsed = started.elapsed();
        eprintln!(
            "adblock-rust engine built from {} bytes in {} ms",
            filters.len(),
            elapsed.as_millis()
        );
        drop(engine);
        assert!(cases().iter().any(|case| case.expected));
    }

    #[test]
    fn network_decisions_match_shared_fixtures() {
        let filters = include_str!("../../app/src/main/assets/adblock_filters.txt");
        let engine = build_engine(filters.to_string());
        let mut mismatches = Vec::new();

        for case in cases() {
            let actual = should_block(&engine, case.url, case.page_url, case.resource_type, "GET");
            if actual != case.expected {
                mismatches.push(format!(
                    "{} [{}] on {}: expected {}, got {}",
                    case.url, case.resource_type, case.page_url, case.expected, actual
                ));
            }
        }

        assert!(mismatches.is_empty(), "{}", mismatches.join("\n"));
    }
}
