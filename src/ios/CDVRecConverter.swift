// buffer を audio にするやつ
class AudioBufferConverter {
    static var lpcmToAACConverter: AVAudioConverter! = nil
    

    // buffer to convertToAAC
    static func convertToAAC(from buffer: AVAudioBuffer, error outError: NSErrorPointer) -> AVAudioCompressedBuffer? {

        let outputFormat = AudioBufferFormatHelper.AACFormat()
        let outBuffer = AVAudioCompressedBuffer(format: outputFormat!, packetCapacity: 8, maximumPacketSize: 768)

        if lpcmToAACConverter == nil {
            let inputFormat = buffer.format
            lpcmToAACConverter = AVAudioConverter(from: inputFormat, to: outputFormat!)
            lpcmToAACConverter.bitRate = 32000
        }

        self.convert(withConverter: lpcmToAACConverter, from: buffer, to: outBuffer, error: outError)
        return outBuffer

    }

    static var aacToLPCMConverter: AVAudioConverter! = nil
    static func convertToPCM(from buffer: AVAudioBuffer, error outError: NSErrorPointer) -> AVAudioPCMBuffer? {
        let outputFormat = AudioBufferFormatHelper.PCMFormat()
        guard let outBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat!, frameCapacity: 4410) else {
            return nil
        }

        //init converter once
        if aacToLPCMConverter == nil {
            let inputFormat = buffer.format

            aacToLPCMConverter = AVAudioConverter(from: inputFormat, to: outputFormat!)
        }

        self.convert(withConverter: aacToLPCMConverter, from: buffer, to: outBuffer, error: outError)

        return outBuffer
    }

    static func convert(withConverter: AVAudioConverter, from sourceBuffer: AVAudioBuffer, to destinationBuffer: AVAudioBuffer, error outError: NSErrorPointer) {
        var newBufferAvailable = true
        let inputBlock : AVAudioConverterInputBlock = { inNumPackets, outStatus in
            if newBufferAvailable {
                outStatus.pointee = .haveData
                newBufferAvailable = false
                return sourceBuffer
            }
            else {
                outStatus.pointee = .noDataNow
                return nil
            }

        }

        let status = withConverter.convert(to: destinationBuffer, error: outError, withInputFrom: inputBlock)
        print("status: \(status.rawValue)")
    }
}
class AudioBufferFormatHelper {
    static func PCMFormat() -> AVAudioFormat? {
        return AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 44100, channels: 1, interleaved: false)
    }

    static func AACFormat() -> AVAudioFormat? {

        var outDesc = AudioStreamBasicDescription(
            mSampleRate: 44100,
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: 0,
            mBytesPerFrame: 0,
            mChannelsPerFrame: 1,
            mBitsPerChannel: 0,
            mReserved: 0)
        let outFormat = AVAudioFormat(streamDescription: &outDesc)
        return outFormat
    }
}