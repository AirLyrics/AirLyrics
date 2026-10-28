//! Bounded loopback HTTP fixture. Never uses the machine's proxy configuration.
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::time::{Duration, Instant};

pub(crate) struct Server {
    pub(crate) url: String,
    requests: Arc<Mutex<Vec<String>>>,
    stop: Arc<AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}

impl Server {
    pub(crate) fn new(responses: Vec<&'static str>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("loopback listener");
        listener.set_nonblocking(true).unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let stop = Arc::new(AtomicBool::new(false));
        let stopped = stop.clone();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let captured = requests.clone();
        let worker = std::thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(5);
            for response in responses {
                let mut stream = loop {
                    if stopped.load(Ordering::Acquire) {
                        return;
                    }
                    assert!(
                        Instant::now() < deadline,
                        "Expected HTTP request never arrived"
                    );
                    match listener.accept() {
                        Ok((stream, _)) => break stream,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            std::thread::sleep(Duration::from_millis(2))
                        }
                        Err(error) => panic!("accept: {error}"),
                    }
                };
                stream
                    .set_read_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                stream
                    .set_write_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                let mut reader = BufReader::new((&mut stream).take(16 * 1024));
                loop {
                    assert!(Instant::now() < deadline, "HTTP headers exceeded deadline");
                    let mut line = String::new();
                    assert!(
                        reader.read_line(&mut line).expect("request headers") > 0,
                        "Missing complete headers within 16 KiB"
                    );
                    if line.starts_with("GET ") {
                        captured.lock().unwrap().push(line.clone());
                    }
                    if line == "\r\n" {
                        break;
                    }
                }
                stream.write_all(response.as_bytes()).expect("response");
            }
        });
        Self {
            url,
            requests,
            stop,
            worker: Some(worker),
        }
    }

    pub(crate) fn requests(&self) -> Vec<String> {
        self.requests.lock().unwrap().clone()
    }

    pub(crate) fn finish(mut self) {
        self.worker
            .take()
            .unwrap()
            .join()
            .expect("HTTP fixture failed");
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Release);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

pub(crate) fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(3))
        .build()
        .unwrap()
}
