use std::io::{self, Read};
use std::{env, fs};

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    if args.next().as_deref() != Some("parse") {
        return Err(
            "usage: apple_probe parse --ttml <path|-> [--output <json>] [--trace <json>]".into(),
        );
    }
    let (mut input, mut output, mut trace) = (None, None, None);
    while let Some(arg) = args.next() {
        let target = match arg.as_str() {
            "--ttml" => &mut input,
            "--output" => &mut output,
            "--trace" => &mut trace,
            _ => return Err(format!("unknown argument: {arg}").into()),
        };
        if target.is_some() {
            return Err(format!("duplicate argument: {arg}").into());
        }
        *target = Some(args.next().ok_or("missing argument value")?);
    }
    let input = input.ok_or("--ttml is required")?;
    let ttml = if input == "-" {
        let mut text = String::new();
        io::stdin().read_to_string(&mut text)?;
        text
    } else {
        fs::read_to_string(input)?
    };
    let (native, sidecar) = airlyrics_lyrics::audit_apple_ttml(&ttml);
    let payload = serde_json::to_string_pretty(&native)?;
    if let Some(path) = output {
        fs::write(path, payload)?;
    } else {
        println!("{payload}");
    }
    if let Some(path) = trace {
        fs::write(path, serde_json::to_string_pretty(&sidecar)?)?;
    }
    if native["ok"] != true {
        return Err("TTML contains no parseable lyric lines".into());
    }
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("apple_probe: {error}");
        std::process::exit(1);
    }
}
