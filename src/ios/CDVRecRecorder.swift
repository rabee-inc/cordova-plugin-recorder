// 参照
// https://github.com/yumemi-ajike/AudioService/blob/master/AudioService/AudioService.swift

class AudioService {
    // バッファ
    var buffer: UnsafeMutableRawPointer
    // オーディオキューオブジェクト
    var audioQueueObject: AudioQueueRef?
    // 再生時のパケット数
    let numPacketsToRead: UInt32 = 1024
    // 録音時のパケット数
    let numPacketsToWrite: UInt32 = 1024
    // 再生/録音時の読み出し/書き込み位置
    var startingPacketCount: UInt32
    // 最大パケット数。（サンプリングレート x 秒数）
    var maxPacketCount: UInt32
    // パケットのバイト数
    let bytesPerPacket: UInt32 = 2
    // 録音時間（＝再生時間）
    let seconds: UInt32 = 10
    
    // Audio format の作成 (読み書きのや)
    var audioFormat: AudioStreamBasicDescription {
        // format
        let formatFlags = AudioFormatFlags(kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked)
        
        return AudioStreamBasicDescription( mSampleRate: 48000,
                                            mFormatID: kAudioFormatLinearPCM, // format
                                            mFormatFlags: formatFlags,
                                            mBytesPerPacket: 2, // per packet
                                            mFramesPerPacket: 1,
                                            mBytesPerFrame: 2,
                                            mChannelsPerFrame: 1,
                                            mBitsPerChannel: 16,
                                            mReserved: 0)
    }
    
    var data: NSData?
    
    // 初期化
    init(_ obj: Any?) {
        startingPacketCount = 0
        maxPacketCount = (48000 * seconds)
        buffer = UnsafeMutableRawPointer(malloc(Int(maxPacketCount * bytesPerPacket)))
    }
    
    deinit {
        buffer.deallocate()
    }
    
    // レコーディングの準備
    private func prepareRecord() {
        var audioFormat = self.audioFormat
        // Audio Queue を作成
        AudioQueueNewInput( &audioFormat,
                            AQAudioQueueInputCallback, // コールバック
                            unsafeBitCast(self, to: UnsafeMutablePointer.self),
                            CFRunLoopGetCurrent(),
                            CFRunLoopMode.commonModes.rawValue,
                            0,
                            &audioQueueObject)
        
        startingPacketCount = 0
        
        // 3つの buffer
        var buffers = Array<AudioQueueBufferRef?>(repeating: nil, count: 3)
        let bufferBiteSize: UInt32 = numPacketsToWrite * audioFormat.mBytesPerPacket
        
        for index in 0..<buffers.count {
            // buffer の確保
            AudioQueueAllocateBuffer(audioQueueObject!, bufferBiteSize, &buffers[index])
            // キューの最後に buffer の追加
            AudioQueueEnqueueBuffer(audioQueueObject!, buffers[index]!, 0, nil)
        }
        
    }
    
    // 録音
    func startRecord() {
        guard audioQueueObject == nil else { return }
        prepareRecord()
        let err: OSStatus = AudioQueueStart(audioQueueObject!, nil)
        print(err)
    }
    
    // 録音再開
    func resumeRecord() {
        guard audioQueueObject == nil else { return }
        let err: OSStatus = AudioQueueStart(audioQueueObject!, nil)
        print(err)
    }
    
    // レコーディングを pause する
    func pauseRecord() {
        data = NSData(bytesNoCopy: buffer, length: Int(maxPacketCount * bytesPerPacket))
        AudioQueueStop(audioQueueObject!, true)
    }
    
    // レコーディングをストップする
    func stopRecord() {
        data = NSData(bytesNoCopy: buffer, length: Int(maxPacketCount * bytesPerPacket))
        AudioQueueStop(audioQueueObject!, true)
        AudioQueueDispose(audioQueueObject!, true)
        audioQueueObject = nil
    }
    
    
    // パケットを書き込む
    func writePackets(inBuffer: AudioQueueBufferRef) {
        
        var packetCount: UInt32 = (inBuffer.pointee.mAudioDataByteSize / bytesPerPacket)
        
        // 残りのパケット数が、書き込むパケット数より少なかったら
        if ((maxPacketCount - startingPacketCount) < packetCount) {
            packetCount = (maxPacketCount - startingPacketCount) // 残りパケット数をパケット数にする
        }
        
        // パケットカウント
        if 0 < packetCount {
            // buffer へ書き込む
            memcpy(buffer.advanced(by: Int(bytesPerPacket * startingPacketCount)), // dst
                   inBuffer.pointee.mAudioData, // data
                   Int(bytesPerPacket * packetCount)) // size
            
            startingPacketCount += packetCount
        }
    }
    

}


// 録音が走るたび
func AQAudioQueueInputCallback(inUserData: UnsafeMutableRawPointer?,
                               inAQ: AudioQueueRef,
                               inBuffer: AudioQueueBufferRef,
                               inStartTime: UnsafePointer<AudioTimeStamp>,
                               inNumberPacketDescriptions: UInt32,
                               inPacketDescs: UnsafePointer<AudioStreamPacketDescription>?) {
    
    
    let audioService = unsafeBitCast(inUserData!, to:AudioService.self)
    audioService.writePackets(inBuffer: inBuffer)
    AudioQueueEnqueueBuffer(inAQ, inBuffer, 0, nil);
    
    if (audioService.maxPacketCount <= audioService.startingPacketCount) {
        audioService.stopRecord()
    }
}
