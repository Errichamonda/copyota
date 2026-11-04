import AppKit
import CryptoKit

setbuf(stdout, nil) // launchd gives us a file, which would otherwise hold logs back

let key = SymmetricKey(data: Data(base64Encoded: Config.key)!)
let url = URL(string: Config.url.replacingOccurrences(of: "http", with: "ws") + "/" + Config.room)!

func paste(_ message: URLSessionWebSocketTask.Message) {
    guard case .string(let body) = message,
          let sealed = Data(base64Encoded: body),
          let box = try? AES.GCM.SealedBox(combined: sealed),
          let plain = try? AES.GCM.open(box, using: key),
          let text = String(data: plain, encoding: .utf8) else {
        print("ignored a message that failed to decrypt")
        return
    }
    NSPasteboard.general.clearContents()
    NSPasteboard.general.setString(text, forType: .string)
}

// A sleeping Mac or a changed network leaves a dead socket that never errors,
// so ping it and drop it if the pong doesn't come back.
func watchdog(_ socket: URLSessionWebSocketTask) async {
    while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(30))
        let timeout = Task {
            try await Task.sleep(for: .seconds(10))
            socket.cancel()
        }
        socket.sendPing { _ in timeout.cancel() }
    }
}

while true {
    let socket = URLSession.shared.webSocketTask(with: url)
    socket.resume()
    let pings = Task { await watchdog(socket) }
    print("connecting")
    do {
        while true { paste(try await socket.receive()) }
    } catch {
        print("disconnected: \(error.localizedDescription)")
    }
    pings.cancel()
    socket.cancel()
    try? await Task.sleep(for: .seconds(3))
}
