import AVFoundation
import Foundation
import Accelerate

@objc(CDVRec) class CDVRec : CDVPlugin, AVAudioPlayerDelegate {
    let bufferSize = 4096

    var engine: AVAudioEngine?
    var recordingDir = ""
    var isRecording = false
    var pushBufferCallBackId: String?
    var commpressProgressCallBackId: String?
    var audioSettings: [String: Any] = [:]
    var audioSession: AVAudioSession?

    // Audio の型定義
    struct Audio: Codable {
        var name: String
        var duration: String
        var path: String
    }
    
    // 録音終了後の音源
    struct RecordedAudio: Codable {
        var audios: [Audio]
        var fullAudio: Audio
        var folderID: String
    }
    
    /* folder structure
     
     // divided file
     Documents/recording/1560480886/divided/hogehoge.wav
     Documents/recording/1560480886/divided/fugafuga.wav
     Documents/recording/1560480886/divided/foofoo.wav
     
     // joined file
     Documents/recording/1560480886/joined/joined_audio0.wav
     Documents/recording/1560480886/joined/joined_audio1.wav
     Documents/recording/1560480886/joined/joined_audio2.wav
     
    */
    
    /*
     1. レコーディングは wav で行う
     2. export が呼ばれる場合はそのまま返す wav で返す
     3. exportWithCompression の場合は m4a で返す
     4. split では
     */

    var folderID: String = "default_id"
    var audioIndex: Int32 = 0
    var folderPath: String?
    var currentAudioName: String? // 現在録音中のファイル
    var currentAudios: [Audio] = []// 現在録音中のファイルを順番を保証して配置
    var currentJoinedAudioName: String? // 連結済みファイルの最新のものの名前
    var queue: [Audio] = []
    
    override func pluginInitialize() {
        print("[cordova plugin REC. intializing]")
        engine = AVAudioEngine()
        recordingDir = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first! + "/recording"
        audioSettings = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: 44100.0,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRatePerChannelKey: 16,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]
        
        // 録音したものを配置するルートフォルダを作成
        if !FileManager.default.fileExists(atPath: URL(fileURLWithPath: recordingDir).path) {
            do {
                try FileManager.default.createDirectory(at: URL(fileURLWithPath: recordingDir), withIntermediateDirectories: true)
            } catch {
                // TODO: エラーハンドリング
            }
        }
    }
    
    @objc func initSettings(_ command: CDVInvokedUrlCommand) {
        guard let settings = command.arguments.first as? [String: Any] else {
            self.cordovaResultError(command, message: "init settings error")
            return
        }
        
        for (key, value) in settings {
            self.audioSettings[key] = value
        }

        // cordova result
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // start recording
    @objc func start(_ command: CDVInvokedUrlCommand) {
        // 既にスタートしてたら エラーを返す
        if isRecording {
            self.cordovaResultError(command, message: "already starting")
            return
        }
        
        // リセットをかける
        audioIndex = 0
        currentAudios = []
        queue = []
        
        let path = self.getNewFolderPath()

        startRecord(path: path)
        
        isRecording = true
        
        // 問題なければ result
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
        self.commandDelegate.send(result, callbackId:command.callbackId)
    }
    
    // stop recording
    @objc func stop(_ command: CDVInvokedUrlCommand) {
        // スタートしていなかったらエラーを返す
        guard isRecording else {
            self.cordovaResultError(command, message: "not starting")
            return
        }
        
        // pause するだけ
        _ = pauseRecord()
        isRecording = false
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // pause recording
    @objc func pause(_ command: CDVInvokedUrlCommand) {
        // スタートしていなかったらエラーを返す
        guard isRecording else {
            self.cordovaResultError(command, message: "not starting")
            return
        }
        
        // レコーディング中断
        _ = pauseRecord()
        isRecording = false
    
        // cordova result
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // resume recording
    @objc func resume(_ command: CDVInvokedUrlCommand) {
        // 既にスタートしてたらエラーを返す
        guard !isRecording else {
            self.cordovaResultError(command, message: "already starting")
            return
        }
        
        let path = self.getCurrentFolderPath()
        self.startRecord(path: path)
        isRecording = true
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs:"pause success")
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // 音声ファイルをエクスポートする
    @objc func export(_ command: CDVInvokedUrlCommand) {
        // 現在の音声ファイルをつなげる
        guard let joinAudio = self.joinRecord() else {
            self.cordovaResultError(command, message: "join record error")
            return
        }
        
        // 送るオーディオファイルの作成
        let recordAudio = RecordedAudio(audios: currentAudios, fullAudio: joinAudio, folderID: folderID)
        
        var sendMessage: [String: Any]
        do {
            // JSON データの形成
            let encoder = JSONEncoder()
            let data = try encoder.encode(recordAudio)
            guard let msg = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
                self.cordovaResultError(command, message: "json serialization error")
                return
            }
            sendMessage = msg
        } catch let err {
            self.cordovaResultError(command, message: "encode error: \(err)")
            return
        }
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: sendMessage)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // 音声バッファを送る
    @objc func onPushBuffer (_ command: CDVInvokedUrlCommand) {
        pushBufferCallBackId = command.callbackId;
    }
    
    // progress bar のコールバック
    @objc func onProgressCompression (_ command: CDVInvokedUrlCommand) {
        commpressProgressCallBackId = command.callbackId;
    }
    
    @objc func getRecordingFolders(_ command: CDVInvokedUrlCommand) {
        do {
            if !FileManager.default.fileExists(atPath: URL(fileURLWithPath: recordingDir).path) {
                try FileManager.default.createDirectory(at: URL(fileURLWithPath: recordingDir), withIntermediateDirectories: true)
            }
            let fileNames = try FileManager.default.contentsOfDirectory(atPath: recordingDir)
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs:fileNames)
             self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        catch let err {
            self.cordovaResultError(command, message: "can't get index: \(err)")
        }
    }
    
    @objc func removeFolder(_ command: CDVInvokedUrlCommand) {
        guard let folderID = command.argument(at: 0, withDefault: String.self) as? String else {
            self.cordovaResultError(command, message: "get folderID error")
            return
        }
        if let err = removeFolder(id: folderID) {
            self.cordovaResultError(command, message: "remove folder error: \(err)")
            return
        }
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs:"removed!")
         self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    @objc func removeCurrentFolder(_ command: CDVInvokedUrlCommand) {
        if (folderID == "") {
            if let err = removeFolder(id: folderID) {
                self.cordovaResultError(command, message: "remove folder error: \(err)")
                return
            }
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs:"removed!")
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        else {
            self.cordovaResultError(command, message: "can't remove")
        }
    }
    
    @objc func setFolder(_ command: CDVInvokedUrlCommand) {
        guard let folderID = command.argument(at: 0, withDefault: String.self) as? String else {
            self.cordovaResultError(command, message: "get folderID error")
            return
        }
        self.folderID = folderID
        
        var audioPath: URL
        do {
            let d = try FileManager.default.contentsOfDirectory(atPath: (recordingDir + "/\(folderID)/divided/"))
            self.audioIndex = Int32(d.count - 1)
            guard let j = try FileManager.default.contentsOfDirectory(atPath: (recordingDir + "/\(folderID)/joined/")).first else {
                self.cordovaResultError(command, message: "contentsOfDirectory error")
                return
            }
            audioPath = URL(fileURLWithPath: (recordingDir + "/\(folderID)/joined/\(j)"))
        } catch let err {
            self.cordovaResultError(command, message: "file manager error: \(err)")
            return
        }
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: audioPath.absoluteString)
         self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    @objc func getAudio(_ command: CDVInvokedUrlCommand) {
        guard let folderID = command.argument(at: 0) as? String else {
            self.cordovaResultError(command, message: "get folderID error")
            return
        }
        self.folderID = folderID
        
        do {
            guard let j = try FileManager.default.contentsOfDirectory(atPath: (recordingDir + "/\(folderID)/joined/")).first else {
                self.cordovaResultError(command, message: "file manager error")
                return
            }
            let audioPath = URL(fileURLWithPath: (recordingDir + "/\(folderID)/joined/\(j)"))
            let asset = AVURLAsset(url:audioPath)
            
            let joinedAudio = Audio(name: "joined_audio", duration: String(asset.duration.value), path: audioPath.absoluteString)
            
            let audio = RecordedAudio(audios: [], fullAudio: joinedAudio, folderID: folderID)
            
            // JSON データの形成
            let encoder = JSONEncoder()
            let data = try encoder.encode(audio)
            
            // Dictionary 型にキャスト
            let sendMessage = try JSONSerialization.jsonObject(with: data, options: []) as! [String: Any]
            
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: sendMessage)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        } catch let err {
            self.cordovaResultError(command, message: "get audio error: \(err)")
        }
    }
    
    // 波形を取得する
    @objc func getWaveForm(_ command: CDVInvokedUrlCommand) {
        guard let id = command.argument(at: 0) as? String,
            let joinedAudioPath = URL(string: id) else {
            // TODO エラーハンドリング
            return
        }
        do {
            let audioFile = try AVAudioFile(forReading: joinedAudioPath)
            let nframe = Int(audioFile.length)
            let PCMBuffer = AVAudioPCMBuffer(pcmFormat: audioFile.processingFormat, frameCapacity: AVAudioFrameCount(nframe))!
            try audioFile.read(into: PCMBuffer)
            guard let floatChannelData = PCMBuffer.floatChannelData else {
                // TODO エラーハンドリング
                return
            }
            let bufferData = Data(buffer: UnsafeMutableBufferPointer<Float>(start:floatChannelData[0], count: nframe))
            let pcmBufferPath = URL(fileURLWithPath: recordingDir + "/\(folderID)/temppcmbuffer")
            try bufferData.write(to: pcmBufferPath)
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: pcmBufferPath.absoluteString)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        } catch let err {
            self.cordovaResultError(command, message: "get wave form error: \(err)")
        }
    }
    
    // 分割する
    @objc func split(_ command: CDVInvokedUrlCommand) {
        guard let s = command.argument(at: 0) as? NSNumber else {
            // TODO: エラーハンドリング
            return
        }
        let seconds = s.floatValue
        
        // Audio Asset 作成
        let currentPath = self.getCurrentFolderPath()
        let audioURL = URL(fileURLWithPath: currentPath.path + "/joined/joined.wav")
        let audioAsset = AVURLAsset(url: audioURL)
        var exportAudio: Audio?
        let semaphore = DispatchSemaphore(value: 0);
        
        // composition 作成
        let composition = AVMutableComposition()
        guard let audioAssetTrack = audioAsset.tracks(withMediaType: AVMediaType.audio).first,
            let audioCompositionTrack = composition.addMutableTrack(withMediaType: AVMediaType.audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            // TODO: エラーハンドリング
            return
        }

        let timescale = Int32(NSEC_PER_SEC)
        let start = CMTimeMakeWithSeconds(0, preferredTimescale: timescale)
        let end = CMTimeMakeWithSeconds(Float64(seconds), preferredTimescale: timescale)
        let range = CMTimeRangeMake(start: start, duration: end)
        // カット
        do {
            try audioCompositionTrack.insertTimeRange(range, of: audioAssetTrack, at: CMTime.zero)
        }
        catch let error {
            print(error) // TODO: ここはエラー返さなくていいの？
        }
        
        //  export
        if let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) {
            let folderPath = URL(fileURLWithPath: recordingDir + "/\(folderID)/joined" , isDirectory: true)
            if !FileManager.default.fileExists(atPath: folderPath.path) {
                try! FileManager.default.createDirectory(atPath: folderPath.path, withIntermediateDirectories: false)
            }
            
            // 一時保存ファイルとして export 後, もとのファイルを削除してリネーム
            let tempPath = folderPath.absoluteString + "temp.wav";
            let cutFilePath = URL(string: tempPath)!
            exportSession.outputFileType = AVFileType.wav
            exportSession.outputURL = cutFilePath
            
            exportSession.exportAsynchronously {
                switch exportSession.status {
                case .completed:
                    
                    // join
                    let joined_folder = URL(string: folderPath.absoluteString + "joined.wav")!
                    
                    // もとの joined.wav を削除
                    if FileManager.default.fileExists(atPath: joined_folder.path) {
                        try! FileManager.default.removeItem( atPath: joined_folder.path )
                    }
                    
                    // ファイル名変更 temp.wav => joined.wav
                    try! FileManager.default.moveItem(atPath: cutFilePath.path, toPath: joined_folder.path)
                    
                    
                    let asset = AVURLAsset(url: joined_folder);
                    
                    exportAudio = Audio(name:"joined_audio", duration: String(asset.duration.value), path: joined_folder.absoluteString)
                    
                    semaphore.signal()
                case .failed, .cancelled:
                    print("[join error: failed or cancelled]", exportSession.error.debugDescription)
                    semaphore.signal()
                case .waiting:
                    print(exportSession.progress);
                default:
                    print("[join error: other error]", exportSession.error.debugDescription)
                    semaphore.signal()
                }
            }
        }
        
        semaphore.wait()
        
        // 送るオーディオファイルの作成
        let record_audio = RecordedAudio(audios: currentAudios, fullAudio: exportAudio!, folderID: folderID)
        
        // JSON データの形成
        let encoder = JSONEncoder()
        let data = try! encoder.encode(record_audio)
        
        // Dictionary 型にキャスト
        let sendMessage = try! JSONSerialization.jsonObject(with: data, options: []) as! [String: Any]
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: sendMessage)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    @objc func exportWithCompression(_ command: CDVInvokedUrlCommand) {
        commandDelegate.run(inBackground: {
            let semaphore = DispatchSemaphore(value: 0)
            var audio: Audio?
            
            if let id = command.argument(at: 0) as? String {
                self.folderID = id
            }
            
            let inputPath = URL(fileURLWithPath: "\(self.recordingDir)/\(self.folderID)/joined/joined.wav")
            let outputPath = URL(fileURLWithPath: "\(self.recordingDir)/\(self.folderID)/joined/joined.m4a")
            
            // file があった場合は削除して作る
            if FileManager.default.fileExists(atPath: outputPath.path) {
                do {
                    try FileManager.default.removeItem(at: outputPath)
                } catch let err {
                    self.cordovaResultError(command, message: "file manager remove item error: \(err)")
                    return // TODO ここは握りつぶした方が良い？
                }
            }
            
            let asset = AVURLAsset(url: inputPath)
            guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else { return }
            session.outputURL = outputPath
            session.outputFileType = .m4a
            session.exportAsynchronously {
                switch (session.status) {
                case .completed:
                    print("[completed -------------------------->]")
                    audio = Audio(name: "joined_audio", duration: String(asset.duration.value), path: outputPath.absoluteString )
                    semaphore.signal()
                    break
                case .failed:
                    print("[failed -------------------------->]")
                    semaphore.signal()
                    break
                case .waiting:
                    print("[waiting -------------------------->]")
                    semaphore.signal()
                    break
                default:
                    break
                }
            }
            
            // プログレスバーのコールバック
            var p = 0;
            var r: CDVPluginResult?
            while(session.status != .completed && session.status != .failed) {
                if p != Int(round(session.progress * 100)) {
                    p = Int(round(session.progress * 100))
                    r = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: String(p))
                    if self.commpressProgressCallBackId != nil {
                        r?.keepCallback = true
                        self.commandDelegate.send(r, callbackId: self.commpressProgressCallBackId)
                    }
                }
            }
            r?.keepCallback = false;

            semaphore.wait()
            
            if let audio = audio {
                // 送るオーディオファイルの作成
                let record_audio = RecordedAudio(audios: [], fullAudio: audio, folderID: self.folderID)
                
                do {
                    // JSON データの形成
                    let encoder = JSONEncoder()
                    let data = try encoder.encode(record_audio)
                    
                    // Dictionary 型にキャスト
                    guard let sendMessage = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] else {
                        // エラーハンドリング
                        return
                    }
                    
                    let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: sendMessage)
                    self.commandDelegate.send(result, callbackId: command.callbackId)
                } catch let err {
                    self.cordovaResultError(command, message: "json encode error: \(err)")
                }
            }
            else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "compression failed")
                self.commandDelegate.send(result, callbackId: command.callbackId)
            }
        })
    }
    
    private func removeFolder(id: String) -> Error? {
        let folderUrl = URL(fileURLWithPath: recordingDir + "/\(id)")
        if FileManager.default.fileExists(atPath: folderUrl.path) {
            do {
                try FileManager.default.removeItem(atPath: folderUrl.path)
            } catch let err {
                return err
            }
        }
        return nil
    }
    
    // start private func
    private func startRecord(path: URL) {
        do {
            // audio setting
            let audioSettings = self.audioSettings
            
            // audio file name
            let timestamp = String(Int(NSDate().timeIntervalSince1970));
            let id = generateId(length: 16)
            currentAudioName = "\(id)_\(timestamp)";
            
            // フォルダがなかったらフォルダ生成
            let folderPath = URL(string: path.absoluteString + "/queue")!;
            if (!FileManager.default.fileExists(atPath: folderPath.absoluteString)) {
                try FileManager.default.createDirectory(at: URL(fileURLWithPath: folderPath.path), withIntermediateDirectories: true)
            }
            
            // base data
            let filePath = folderPath.appendingPathComponent("\(currentAudioName!).wav")
            
            // audio file
            let audioFile = try! AVAudioFile(forWriting: filePath, settings: audioSettings)
            
            // セッションを作成、有効化
            self.audioSession = AVAudioSession.sharedInstance()
            try self.audioSession?.setCategory(AVAudioSession.Category.playAndRecord)
            try self.audioSession?.setActive(true)
            
            // write buffer
            self.engine?.inputNode.installTap(onBus: 0, bufferSize: UInt32(self.bufferSize), format: nil) { (buffer:AVAudioPCMBuffer, when:AVAudioTime) in
                // call back が登録されていたら
                if self.pushBufferCallBackId != nil {
                    let b = Array(UnsafeBufferPointer(start: buffer.floatChannelData![0], count:Int(buffer.frameLength)))
                    let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: b)
                    result?.keepCallback = true
                    self.commandDelegate.send(result, callbackId: self.pushBufferCallBackId)
                }
                do {
                    try audioFile.write(from: buffer)
                } catch let err {
                    print("[cdv plugin REC: error]", err)
                }
            }

            // engine start
            do {
                try self.engine?.start()
                audioIndex += 1 // increment index
            } catch let error {
                print("[cdv plugin REC] engin start error", error)
            }
        } catch let error {
            print("[cdv plugin REC] Audio file error", error)
        }
    }
    
    // 音声をとめる
    private func pauseRecord() -> Bool {
        // stop engine
        self.engine?.stop()
        self.engine?.inputNode.removeTap(onBus: 0)
        
        do {
            // セッションを非アクティブ化
            try self.audioSession?.setActive(false)
            self.audioSession = nil
        } catch let err {
            // エラーハンドリング
        }
        
        // 現在録音したデータを queue に追加する
        let folderPath = getCurrentFolderPath().absoluteString
        let fullAudioPath = folderPath + "queue/\(currentAudioName!).wav"
        let asset = AVURLAsset(url: URL(string: fullAudioPath)!)
        let data = Audio(name: currentAudioName!, duration: String(asset.duration.value), path: fullAudioPath)
        queue.append(data)
        
        // 追加が終わったら true
        return true
    }
    
    private func getCurrentJoinedAudioURL() -> URL {
        return URL(fileURLWithPath: recordingDir + "/\(folderID)/joined/joined.wav")
    }
    
    private func createNewFolder() -> String {
        // 新しい folder id を生成してそこに保存する (unixtime stamp)
        folderID = String(Int(NSDate().timeIntervalSince1970))
        let path = recordingDir + "/\(folderID)"
        do {
            try FileManager.default.createDirectory(atPath: path, withIntermediateDirectories: true, attributes: nil)
        } catch {
            print("[cdv plugin rec: create folder error]")
        }
        return path
    }
    
    private func getNewFolderPath() -> URL {
        return URL(string: createNewFolder())!
    }
    
    private func getCurrentFolderPath() -> URL {
        let documentDir = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first!
        return URL(fileURLWithPath: "\(documentDir)/recording/\(folderID)", isDirectory: true)
    }
    // random id の取得
    private func generateId(length: Int) -> String {
        let base = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        var randomString: String = ""
        
        for _ in 0..<length {
            let randomValue = arc4random_uniform(UInt32(base.count))
            randomString += String(base[base.index(base.startIndex, offsetBy: Int(randomValue))])
        }
        return randomString
    }
    // folder 内のオーディオファイルを連結して返す
    private func joinRecord() -> Audio? {
        var nextStartTime = CMTime.zero
        var result: Audio?
        let composition = AVMutableComposition()
        let track = composition.addMutableTrack(withMediaType: AVMediaType.audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        let semaphore = DispatchSemaphore(value: 0)
        
        
        let joinedFilePath = URL(fileURLWithPath: recordingDir + "/\(folderID)/joined/joined.wav", isDirectory: false)
        let isJoinedFile = FileManager.default.fileExists(atPath: joinedFilePath.path);
        var audio_files:[String] = [];
        
        var currentQueue = queue
        let audioFolderPath = recordingDir + "/\(folderID)/divided"
        
        if isJoinedFile {
            audio_files.append(joinedFilePath.absoluteString)
        }
        
        currentQueue    .forEach { (item:Audio) in
            audio_files.append(item.path)
        }
        
        for audio in audio_files {
            let fullPath = URL(string: audio)!
            if FileManager.default.fileExists(atPath:  fullPath.path) {
                let asset = AVURLAsset(url: fullPath)
                if let assetTrack = asset.tracks.first {
                    let timeRange = CMTimeRange(start: CMTime.zero, duration: asset.duration)
                    do {
                        try track?.insertTimeRange(timeRange, of: assetTrack, at: nextStartTime)
                        nextStartTime = CMTimeAdd(nextStartTime, timeRange.duration)
                    } catch {
                        print("concatenateError : \(error)")
                    }
                }
            }
        }
        
        if let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) {
            let folderPath = URL(fileURLWithPath: recordingDir + "/\(folderID)/joined" , isDirectory: true)
            if !FileManager.default.fileExists(atPath: folderPath.path) {
               try! FileManager.default.createDirectory(atPath: folderPath.path, withIntermediateDirectories: false)
            }
            
            let tempPath = folderPath.absoluteString + "temp.wav";
            let concatFileSaveURL = URL(string: tempPath)!
            
            exportSession.outputFileType = AVFileType.wav
            exportSession.outputURL = concatFileSaveURL
            
            exportSession.exportAsynchronously(completionHandler: {
                switch exportSession.status {
                
                // ファイル連結成功時
                case .completed:
                    let joinedFolder = URL(string: folderPath.absoluteString + "joined.wav")!
                    
                    // もとの joined.wav を削除
                    if FileManager.default.fileExists(atPath: joinedFolder.path) {
                        try! FileManager.default.removeItem(atPath: joinedFolder.path)
                    }
                    
                    // ファイル名変更 temp.wav => joined.wav
                    try! FileManager.default.moveItem(atPath: concatFileSaveURL.path, toPath: joinedFolder.path)
                    
                    // Queue フォルダーに移動。移動後 queue -> divided フォルダーに移動
                    currentQueue = currentQueue.map { (item) in
                        let from = URL(string: item.path)!
                        let to = URL(fileURLWithPath: "\(audioFolderPath)/\(item.name).wav")
                        
                        if !FileManager.default.fileExists(atPath: audioFolderPath) {
                            try! FileManager.default.createDirectory(atPath: audioFolderPath, withIntermediateDirectories: true)
                        }
                        
                        try! FileManager.default.moveItem(atPath: from.path, toPath: to.path)
                        return Audio(name: item.name, duration: item.duration, path: to.absoluteString)
                    }
                    
                    self.currentAudios.append(contentsOf: currentQueue)
                    
                    self.queue = [] // Queue をリセット
                    
                    let asset = AVURLAsset(url: joinedFolder);
                    
                    result = Audio(name:"joined_audio", duration: String(asset.duration.value), path: joinedFolder.absoluteString)
                    
                    semaphore.signal()
                case .failed, .cancelled:
                    print("[join error: failed or cancelled]", exportSession.error.debugDescription)
                    semaphore.signal()
                case .waiting:
                    print(exportSession.progress);
                default:
                    print("[join error: other error]", exportSession.error.debugDescription)
                    semaphore.signal()
                }
            })
        }
        
        semaphore.wait()
        return result
    }

    private func cordovaResultError(_ command: CDVInvokedUrlCommand, message: String) {
        let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: message)
        self.commandDelegate.send(result, callbackId:command.callbackId)
    }
}
