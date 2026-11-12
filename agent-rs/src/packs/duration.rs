//! A duration as a pack, a request and the command line write one: a number and its unit,
//! `250ms`, `2s`, `1.5s`, `10m`, `1h`.

use std::time::Duration;

/// A duration as written; none when it isn't one.
pub fn parse(text: &str) -> Option<Duration> {
    let split = text.find(|c: char| !c.is_ascii_digit() && c != '.')?;
    let (amount, unit) = text.split_at(split);
    if !is_amount(amount) {
        return None;
    }
    let amount: f64 = amount.parse().ok()?;
    let millis = match unit {
        "ms" => amount,
        "s" => amount * 1000.0,
        "m" => amount * 60_000.0,
        "h" => amount * 3_600_000.0,
        _ => return None,
    };
    Some(Duration::from_millis(millis.round() as u64))
}

/// Digits, then perhaps a point and more digits: `2`, `1.5`; never `.5` or `2.`.
fn is_amount(text: &str) -> bool {
    let mut parts = text.splitn(2, '.');
    let whole = parts.next().unwrap_or("");
    let fraction = parts.next();
    !whole.is_empty()
        && whole.bytes().all(|b| b.is_ascii_digit())
        && fraction.is_none_or(|f| !f.is_empty() && f.bytes().all(|b| b.is_ascii_digit()))
}

/// A duration as a pack writes one, in its largest whole unit.
pub fn show(duration: Duration) -> String {
    let millis = duration.as_millis();
    if millis % 3_600_000 == 0 {
        format!("{}h", millis / 3_600_000)
    } else if millis % 60_000 == 0 {
        format!("{}m", millis / 60_000)
    } else if millis % 1000 == 0 {
        format!("{}s", millis / 1000)
    } else {
        format!("{millis}ms")
    }
}

/// A duration in seconds as the command line says one waited: `5.0 s`, `1.5 s`.
pub fn seconds(duration: Duration) -> String {
    let seconds = duration.as_millis() as f64 / 1000.0;
    if seconds.fract() == 0.0 {
        format!("{seconds:.1}")
    } else {
        format!("{seconds}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn durations_read_and_show_as_a_pack_writes_them() {
        let rows: &[(&str, Option<u64>)] = &[
            ("250ms", Some(250)),
            ("2s", Some(2000)),
            ("1.5s", Some(1500)),
            ("10m", Some(600_000)),
            ("1h", Some(3_600_000)),
            ("0.5ms", Some(1)),
            ("2", None),
            ("s", None),
            (".5s", None),
            ("2.s", None),
            ("2 s", None),
            ("-2s", None),
            ("2d", None),
            ("", None),
        ];
        for (text, millis) in rows {
            assert_eq!(parse(text).map(|d| d.as_millis() as u64), *millis, "{text}");
        }
        for (millis, shown) in [
            (250, "250ms"),
            (2000, "2s"),
            (1500, "1500ms"),
            (600_000, "10m"),
            (3_600_000, "1h"),
        ] {
            assert_eq!(show(Duration::from_millis(millis)), shown);
        }
        assert_eq!(seconds(Duration::from_secs(5)), "5.0");
        assert_eq!(seconds(Duration::from_millis(1500)), "1.5");
    }
}
