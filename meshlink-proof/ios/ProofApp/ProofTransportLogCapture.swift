import Darwin
import Foundation

final class ProofTransportLogCapture {
    private var capturedStdoutBuffer: String = ""
    private var stdoutPipe: Pipe?
    private var stdoutOriginalDescriptor: Int32 = -1

    func startIfNeeded(
        launchConfig: ProofLaunchConfig,
        appendLog: @escaping (String) -> Void
    ) {
        guard launchConfig.benchmarkTransport == .meshLink,
              launchConfig.transportTelemetryEnabled,
              stdoutOriginalDescriptor == -1 else {
            return
        }
        let pipe = Pipe()
        fflush(stdout)
        stdoutOriginalDescriptor = dup(STDOUT_FILENO)
        dup2(pipe.fileHandleForWriting.fileDescriptor, STDOUT_FILENO)
        stdoutPipe = pipe
        pipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            guard !data.isEmpty else {
                return
            }
            let output = String(decoding: data, as: UTF8.self)
            DispatchQueue.main.async {
                self.ingest(output: output, appendLog: appendLog)
            }
        }
    }

    func stop() {
        guard stdoutOriginalDescriptor != -1 else {
            return
        }
        fflush(stdout)
        dup2(stdoutOriginalDescriptor, STDOUT_FILENO)
        close(stdoutOriginalDescriptor)
        stdoutOriginalDescriptor = -1
        stdoutPipe?.fileHandleForReading.readabilityHandler = nil
        stdoutPipe = nil
    }

    private func ingest(output: String, appendLog: (String) -> Void) {
        capturedStdoutBuffer.append(output)
        while let newlineRange = capturedStdoutBuffer.range(of: "\n") {
            let line = String(capturedStdoutBuffer[..<newlineRange.lowerBound])
            capturedStdoutBuffer.removeSubrange(capturedStdoutBuffer.startIndex...newlineRange.lowerBound)
            if line.contains("MeshLinkTransport") {
                appendLog(line)
            }
        }
    }
}
