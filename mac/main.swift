import AppKit
import CoreImage
import CryptoKit

setbuf(stdout, nil) // launchd gives us a file, which would otherwise hold logs back

// Draws a QR code with dark-on-white half blocks, which scan on dark terminals too.
func printQR(_ text: String) {
    let filter = CIFilter(name: "CIQRCodeGenerator")!
    filter.setValue(Data(text.utf8), forKey: "inputMessage")
    filter.setValue("L", forKey: "inputCorrectionLevel")
    let image = filter.outputImage!
    let size = Int(image.extent.width) // one pixel per module
    var pixels = [UInt8](repeating: 255, count: size * size)
    CIContext().render(image, toBitmap: &pixels, rowBytes: size, bounds: image.extent,
                       format: .L8, colorSpace: CGColorSpaceCreateDeviceGray())
    let quiet = 2
    func dark(_ x: Int, _ y: Int) -> Bool {
        x >= 0 && y >= 0 && x < size && y < size && pixels[y * size + x] < 128
    }
    for y in stride(from: -quiet, to: size + quiet, by: 2) {
        var row = "\u{1b}[30;47m"
        for x in -quiet..<size + quiet {
            switch (dark(x, y), dark(x, y + 1)) {
            case (true, true): row += "█"
            case (true, false): row += "▀"
            case (false, true): row += "▄"
            default: row += " "
            }
        }
        print(row + "\u{1b}[0m")
    }
}

if CommandLine.arguments.count == 3, CommandLine.arguments[1] == "qr" {
    printQR(CommandLine.arguments[2])
    exit(0)
}

// ~/.config/copyota/env holds ROOM, KEY and URL as KEY=value lines, written by `copyota setup`.
var config: [String: String] = [:]
let file = FileManager.default.homeDirectoryForCurrentUser.appending(path: ".config/copyota/env")
for line in ((try? String(contentsOf: file, encoding: .utf8)) ?? "").split(separator: "\n") {
    let pair = line.split(separator: "=", maxSplits: 1) // the base64 key ends in "="
    if pair.count == 2 { config[String(pair[0])] = String(pair[1]) }
}
guard let relay = config["URL"], let room = config["ROOM"],
      let keyData = config["KEY"].flatMap({ Data(base64Encoded: $0) }) else {
    print("not set up, run copyota setup")
    exit(1)
}
let key = SymmetricKey(data: keyData)
let url = URL(string: relay.replacingOccurrences(of: "http", with: "ws") + "/" + room)!

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
