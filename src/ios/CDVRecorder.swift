import AVFoundation
import Foundation
import Accelerate
import Alamofire

@objc(CDVRecorder) class CDVRecorder : CDVPlugin, AVAudioPlayerDelegate {
    var bufferSize = 4096
    // メモリからローカル変数が消えるバグを回避するための変数
    var eq: AVAudioUnitEQ! = nil
    var engine: AVAudioEngine?
    // 旧仕様のフォルダ。今後は使わない
    var recordingDir = ""
    
    var audioDir = ""
    var tempDir = ""
    var tempWaveFormPath = ""
    var tempWavPath = ""
    var playableAudioName = ""
    var joinedPath = ""
    var compressionPath = ""
    var audioListDir = ""
    var tempAudioListDir = ""
    
    var effectAudioDir = ""
    var changeDecibelDir = ""
    
    var versionsDir = ""
    
    var maxVolume: Float = 0
    var volumeCheckCount: Int = 0
    
    var isRecording = false
    var pushBufferCallBackId: String?
    var changeConnectedEarPhoneStatusCallBackId: String?
    var completeDownloadCallbackId: String?
    var downloadBgmProgressCallbackId: String?
    
    var commpressProgressCallBackId: String?
    var headphonesConnected = false
    var audioMixer: AVAudioMixerNode?
    private var progress: Progress?
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
    
    // エラーコード定義
    enum ErrorCode: String {
        case permissionError = "permission_error"
        case argumentError = "argument_error"
        case folderManipulationError = "folder_manipulation_error"
        case jsonSerializeError = "json_serialize_error"
        // for plugin send
        func toDictionary(message: String) -> [String:Any] {
            return ["code": self.rawValue, "message": message]
        }
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
    var currentJoinedAudioName: String? // 連結済みファイルの最新のものの名前
    var bgms: [CDVRecorderBgm] = []
    
    // called starting app
    override func pluginInitialize() {
        bufferSize = 4096
        maxVolume = 0
        volumeCheckCount = 0
        print("[cordova plugin REC. intializing]")
        // エンジンとミキサーの初期化
        engine = AVAudioEngine()
        audioMixer = AVAudioMixerNode()
        engine?.attach(audioMixer!)
        
        // download progress の初期化
        progress = Progress()
        _ = progress?.observe(\.fractionCompleted, changeHandler: { p,_  in
            print(p.fractionCompleted)
        })
        let documentDir = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first!
        
        playableAudioName = "joined.wav"
        recordingDir = documentDir + "/recording"
        audioDir = documentDir + "/CDVRecorderAudio"
        tempDir = documentDir + "/CDVRecorderTemp"
        tempWaveFormPath = tempDir + "/waveform"
        tempWavPath = tempDir + "/temp.wav"
        joinedPath = audioDir + "/\(playableAudioName)"
        compressionPath = audioDir + "/joined.m4a"
        audioListDir = audioDir + "/audios"
        tempAudioListDir = tempDir + "/audios"
        effectAudioDir = audioDir + "/effects"
        changeDecibelDir = effectAudioDir + "/decibel"
        versionsDir = audioDir + "/versions"
        bgms = []
        let initTargetDirs = [tempDir, audioListDir, tempAudioListDir, effectAudioDir, changeDecibelDir, versionsDir]
        initTargetDirs.forEach({dir in
            do {
                try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true, attributes: nil)
            }
            catch let error {
                print(error)
            }
        })
        removeDirContents(atPath: changeDecibelDir)
    }
    
    
    // initalize
    @objc func initialize(_ command: CDVInvokedUrlCommand) {
        // 録音する許可がされているか？
        AVAudioSession.sharedInstance().requestRecordPermission {[weak self] granted in
            guard let self = self else { return }
            if !granted {
                let message = ErrorCode.permissionError.toDictionary(message: "deny permission")
                let result = CDVPluginResult(
                    status: CDVCommandStatus_ERROR,
                    messageAs:message
                    )
                self.commandDelegate.send(result, callbackId: command.callbackId)
            }
            else {
                self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true), callbackId:command.callbackId)
            }
        }
        
        // 通知センター登録
        NotificationCenter.default.addObserver(self, selector: #selector(self.handleAudioRouteChange(notification:)), name: NSNotification.Name.AVAudioSessionRouteChange, object: nil)
        // 録音したものを配置するルートフォルダを作成
        if !FileManager.default.fileExists(atPath: URL(fileURLWithPath: audioDir).path) {
            do {
                try FileManager.default.createDirectory(at: URL(fileURLWithPath: audioDir), withIntermediateDirectories: true)
            } catch {
                
                let result = CDVPluginResult(
                    status: CDVCommandStatus_ERROR,
                    messageAs: ErrorCode.folderManipulationError.toDictionary(message: "can't create recording folder")
                    )
                self.commandDelegate.send(result, callbackId: command.callbackId)
            }
        }
//        // 録音したものを配置するルートフォルダを作成
//        if !FileManager.default.fileExists(atPath: URL(fileURLWithPath: recordingDir).path) {
//            do {
//                try FileManager.default.createDirectory(at: URL(fileURLWithPath: recordingDir), withIntermediateDirectories: true)
//            } catch {
//
//                let result = CDVPluginResult(
//                    status: CDVCommandStatus_ERROR,
//                    messageAs: ErrorCode.folderManipulationError.toDictionary(message: "can't create recording folder")
//                    )
//                self.commandDelegate.send(result, callbackId: command.callbackId)
//            }
//        }
    }

    
    // initialize setting
    @objc func initSettings(_ command: CDVInvokedUrlCommand) {
        // TODO: 使わないので後で消す
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
        removeAudios()
        
        // 途中でキルされた場合に音声がバグらないようにちゃんと終了処理をする
        let notificationCenter = NotificationCenter.default
        notificationCenter.addObserver(
            self,
            selector: #selector(CDVRecorder.pauseRecordForNotification(notification:)),
            name: NSNotification.Name.UIApplicationWillTerminate,
            object: nil)
        
        startRecord(path: URL(string: joinedPath)!)
        
        isRecording = true
        
        // 問題なければ result
        let resultData = ["sampleRate": getInputFormat()?.sampleRate]
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: resultData as [AnyHashable : Any])
        self.commandDelegate.send(result, callbackId:command.callbackId)
    }
    
    @objc func pauseRecordForNotification(notification: NSNotification) {
        _ = try! pauseRecord()
        let notificationCenter = NotificationCenter.default
        notificationCenter.removeObserver(self, name: NSNotification.Name.UIApplicationWillTerminate, object: nil)
    }
    
    // stop recording
    @objc func stop(_ command: CDVInvokedUrlCommand) {
        // スタートしていなかったらエラーを返す
        guard isRecording else {
            self.cordovaResultError(command, message: "not starting")
            return
        }
        
        // pause するだけ
        do {
            _ = try pauseRecord()
            isRecording = false
            
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        catch let err {
            self.cordovaResultError(command, message: "can't stop recording\(err)")
        }

    }
    
    @objc func importAudio(_ command: CDVInvokedUrlCommand){
        guard let currentPath = command.argument(at: 0) as? String,
            let currentAudioURL = URL(string: currentPath) else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: importAudio] First argument required. Please specify folder id")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        do {
            try FileManager.default.moveItem(atPath: currentAudioURL.path, toPath: joinedPath)
            removeAudios()
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs:folderID)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        catch let err {
            let message = ErrorCode.folderManipulationError.toDictionary(message: "can't get recording folders: \(err)")
            let result = CDVPluginResult(
             status: CDVCommandStatus_ERROR,
             messageAs:message
             )
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
    }
    
    // atPath 内のすべてのファイルを削除する
    private func removeDirContents(atPath: String) {
        let fileNames = try! FileManager.default.contentsOfDirectory(atPath: atPath)
        for file in fileNames {
            try! FileManager.default.removeItem(atPath: "\(atPath)/\(file)")
        }
    }
    
    private func removeAudios() {
        let fileNames = try! FileManager.default.contentsOfDirectory(atPath: audioListDir)
        for file in fileNames {
            try! FileManager.default.removeItem(atPath: "\(audioListDir)/\(file)")
        }
    }
    
    private func removeTempAudios() {
        let fileNames = try! FileManager.default.contentsOfDirectory(atPath: tempAudioListDir)
        for file in fileNames {
            try! FileManager.default.removeItem(atPath: "\(tempAudioListDir)/\(file)")
        }
    }
    
    private func removeTempWav() {
        if FileManager.default.fileExists(atPath: tempWavPath) {
            try! FileManager.default.removeItem(atPath: tempWavPath)
        }
    }
    
    private func removeVersions() {
        removeDirContents(atPath: versionsDir)
    }

    // pause recording
    @objc func pause(_ command: CDVInvokedUrlCommand) {
        // スタートしていなかったらエラーを返す
        guard isRecording else {
            self.cordovaResultError(command, message: "not starting")
            return
        }
        
        // レコーディング中断
        do {
            _ = try pauseRecord()
            isRecording = false
        
            // cordova result
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        catch let err {
            self.cordovaResultError(command, message: "can't pause recording\(err)")
        }

    }
    
    // resume recording
    @objc func resume(_ command: CDVInvokedUrlCommand) {
        // 既にスタートしてたらエラーを返す
        guard !isRecording else {
            self.cordovaResultError(command, message: "already starting")
            return
        }
        
        // 途中でキルされた場合に音声がバグらないようにちゃんと終了処理をする
        let notificationCenter = NotificationCenter.default
        notificationCenter.addObserver(
            self,
            selector: #selector(CDVRecorder.pauseRecordForNotification(notification:)),
            name: NSNotification.Name.UIApplicationWillTerminate,
            object: nil)
        
        let files = try! FileManager.default.contentsOfDirectory(atPath: audioListDir)
        self.startRecord(path: URL(string: "\(audioListDir)/\(files.count).wav")!)
        isRecording = true
        
        // sample rate 取得
        let resultData = ["sampleRate": getInputFormat()?.sampleRate]
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: resultData as [AnyHashable : Any])
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // 音声ファイルをエクスポートする
    @objc func export(_ command: CDVInvokedUrlCommand) {
        // 現在の音声ファイルをつなげる
        let err = generateJoinedAudio()
        if err != nil {
            self.cordovaResultError(command, message: "join record error")
            return
        }
        let sendMessage = getJoinedAudioData()
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
            let message = ErrorCode.folderManipulationError.toDictionary(message: "can't get recording folders: \(err)")
            let result = CDVPluginResult(
             status: CDVCommandStatus_ERROR,
             messageAs:message
             )
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
    }
    
    // 音声の復元が可能かどうか。 joined.wav を生成可能かどうか
    @objc func canRestore(_ command: CDVInvokedUrlCommand) {
        var message = false
        // versions に playableAudioName がある場合は移動する
        _ = restoreFromVersion()
        removeVersions()
        // 新仕様のファイルが存在する場合は、それを返す
        message = FileManager.default.fileExists(atPath: joinedPath)
        
        // 新仕様のファイルが存在しない場合は、 結合前のファイルが一つでも存在するかチェック
        if !message {
            if FileManager.default.fileExists(atPath: audioListDir) {
                do {
                    // 中にファイルが存在するかどうか
                    let j = try FileManager.default.contentsOfDirectory(atPath: audioListDir).first
                    message = j != nil
                }
                catch let err {
                    print(err)
                }
            }
        }
        
        // 旧仕様のファイルチェック
        if !message {
            do {
                var fileNames = try FileManager.default.contentsOfDirectory(atPath: recordingDir)
                print(fileNames)
                if fileNames.count != 0 {
                    fileNames.sort { $0 > $1 }
                    let folderFirst: String = fileNames.first!
                    let oldJoinedPath = "\(recordingDir)/\(folderFirst)/joined/joined.wav"
                    if FileManager.default.fileExists(atPath: oldJoinedPath) {
                        try! FileManager.default.moveItem(atPath: oldJoinedPath, toPath: joinedPath)
                        message = true
                        try! FileManager.default.removeItem(atPath: recordingDir)
                    }
                }
            }
            catch let err {
                print(err)
            }
        }
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: message)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // 音声を再生可能な状態で復元する
    @objc func restore(_ command: CDVInvokedUrlCommand) {
        let err = generateJoinedAudio()
        if err == nil {
            // 成功時の処理
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: getJoinedAudioData())
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        else {
            // 失敗時の処理
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: err)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
    }
    
    private func getJoinedAudioData() -> [String: Any] {
        let audioPath = URL(fileURLWithPath: joinedPath)
        let asset = AVURLAsset(url:audioPath)
        return [
            "audios": [],
            "full_audio": [
                "path": audioPath.absoluteString,
                "duration": String(asset.duration.seconds),
                "name": "joined_audio"
            ],
        ] as [String : Any]
    }
    
    // 指定したバージョンを復元する
    private func restoreFromVersion(version: Int) -> String? {
        let dir = "\(versionsDir)/\(version)"
        var err: String? = nil
        let playableAudioPath = "\(dir)/\(playableAudioName)"
        if FileManager.default.fileExists(atPath: playableAudioPath) {
            do {
                if FileManager.default.fileExists(atPath: joinedPath) {
                    try FileManager.default.removeItem(atPath: joinedPath)
                }
                try FileManager.default.moveItem(atPath: playableAudioPath, toPath: joinedPath)
            }
            catch let e {
                err = "復元処理に失敗しました: " + e.localizedDescription
            }
        }
        else {
            err = "ファイルが存在しません"
        }
        return err
    }
    
    // 一番最新の version から復元する (あとで audios とかの結合可能かどうかも見るが一旦 playableAudioName があるかだけ確認する)
    // ファイルがなければ、文字列で返す
    // ファイルがあれば、コピーして nil を返す
    private func restoreFromVersion() -> String? {
        var err: String? = nil
        do {
            // ファイルが有るかどうか確認する
            var versions = try FileManager.default.contentsOfDirectory(atPath: versionsDir)
            if versions.count <= 0 {
                err = "ファイルが存在しません"
            }
            else {
                // 大きい順にソート
                versions.sort {$0 > $1}
                for version in versions {
                    err = restoreFromVersion(version: Int(version)!)
                    if err == nil {
                        break
                    }
                }
            }
        }
        catch let e {
            err = e.localizedDescription
        }
        return err
    }
    
    // 指定したバージョンにコピーを取る
    private func setToVersion(version: Int) throws {
        let toDir = versionsDir + "/\(version)"
        if !FileManager.default.fileExists(atPath: toDir) {
            try FileManager.default.createDirectory(atPath: toDir, withIntermediateDirectories: true, attributes: nil)
        }
        let toPath = toDir + "/\(playableAudioName)"
        if FileManager.default.fileExists(atPath: toPath) {
            try FileManager.default.removeItem(atPath: toPath)
        }
        try FileManager.default.copyItem(atPath: joinedPath, toPath: toPath)
    }
    
    // 新しいバージョンを生成する
    private func addNewVersion() throws -> Int {
        let versions = try FileManager.default.contentsOfDirectory(atPath: versionsDir)
        try setToVersion(version: versions.count)
        return versions.count
    }
    
    // 指定したversionを削除する
    private func removeVersion(version: Int) throws {
        let dir = versionsDir + "/\(version)"
        if FileManager.default.fileExists(atPath: dir) {
            try FileManager.default.removeItem(atPath: dir)
        }
    }
    
    // 新しい version にバックアップを取る
    @objc func addNewVersion(_ command: CDVInvokedUrlCommand) {
        do {
            let version = try addNewVersion()
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: version)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        catch let err {
            sendCordovaError(command: command, err: err)
            return
        }
    }
    
    // 一番新しいバージョンで復元する
    @objc func restoreFromVersion(_ command: CDVInvokedUrlCommand) {
        let err = restoreFromVersion()
        if err == nil {
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        else {
            sendCordovaError(command: command, err: NSError(domain: err!, code: -1, userInfo: nil))
        }
    }
    
    // すべてのversionを削除
    @objc func removeVersions(_ command: CDVInvokedUrlCommand) {
        removeVersions()
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // 音声ファイルを結合する
    private func concatAudio(files: [String], outputPath: String) -> String? {
        var nextStartTime = kCMTimeZero
        let composition = AVMutableComposition()
        let track = composition.addMutableTrack(withMediaType: AVMediaType.audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        let semaphore = DispatchSemaphore(value: 0)
        removeTempWav()
        let concatFileSaveURL = URL(fileURLWithPath: tempWavPath)
        let joinedURL = URL(fileURLWithPath: outputPath)
        
        var error: String? = nil
        
        // track に各音声を横並びに追加していく
        for file in files {
            let fullPath = URL(fileURLWithPath: file)
            if FileManager.default.fileExists(atPath: fullPath.path) {
                let asset = AVURLAsset(url: fullPath)
                if let assetTrack = asset.tracks.first {
                    let timeRange = CMTimeRange(start: kCMTimeZero, duration: asset.duration)
                    do {
                        try track?.insertTimeRange(timeRange, of: assetTrack, at: nextStartTime)
                        nextStartTime = CMTimeAdd(nextStartTime, timeRange.duration)
                    } catch let err {
                        error = "concatenateError : \(err)"
                        print(error)
                    }
                }
            }
        }
        
        if error != nil {
            return error
        }
        
        // 結合するためのセッションを開始(実際には音声を横並び状態にしたものを一つ音声として出力するという処理)
        if let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) {
            
            exportSession.outputFileType = AVFileType.wav
            exportSession.outputURL = concatFileSaveURL
            
            // 非同期で 出力
            exportSession.exportAsynchronously(completionHandler: {
                switch exportSession.status {
                
                // ファイル連結成功時
                case .completed:
                    // もとの joined.wav を削除
                    if FileManager.default.fileExists(atPath: joinedURL.path) {
                        try! FileManager.default.removeItem(atPath: joinedURL.path)
                    }
                    // ファイル名変更 temp.wav => joined.wav
                    try! FileManager.default.moveItem(atPath: concatFileSaveURL.path, toPath: joinedURL.path)
                    
                    semaphore.signal()
                case .failed, .cancelled:
                    error = "[join error: failed or cancelled]" + exportSession.error.debugDescription
                    print(error)
                    semaphore.signal()
                case .waiting:
                    print(exportSession.progress);
                default:
                    error = "[join error: other error]" + exportSession.error.debugDescription
                    print(error)
                    semaphore.signal()
                }
            })
        }
        
        semaphore.wait()
        
        return error
    }
    
    private func generateJoinedAudio() -> String? {
        // 結合したいファイルの配列を生成する
        // 配列の順番に結合する
        var targets:[String] = []
        if FileManager.default.fileExists(atPath: joinedPath) {
            targets.append(joinedPath)
        }
        if FileManager.default.fileExists(atPath: audioListDir) {
            do {
                var audioList = try FileManager.default.contentsOfDirectory(atPath: audioListDir)
                audioList.sort {$0 < $1}
                for audioPath in audioList {
                    targets.append("\(audioListDir)/\(audioPath)")
                }
            }
            catch let err {
                print(err)
            }
        }
        // 結合処理
        let err = concatAudio(files: targets, outputPath: joinedPath)
        if err == nil {
            removeAudios()
        }
        return err
    }
    
    @objc func removeFolder(_ command: CDVInvokedUrlCommand) {
        if let err = removeFolder() {
            self.cordovaResultError(command, message: "remove folder error: \(err)")
            return
        }
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs:"removed!")
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // 未使用
    @objc func setFolder(_ command: CDVInvokedUrlCommand) {
        guard let folderID = command.argument(at: 0, withDefault: String.self) as? String else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: setFolder] First argument required. Please specify folder id")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        self.folderID = folderID
        
        var audioPath: URL
        do {
            let d = try FileManager.default.contentsOfDirectory(atPath: (recordingDir + "/\(folderID)/divided/"))
            self.audioIndex = Int32(d.count - 1)
            guard let j = try FileManager.default.contentsOfDirectory(atPath: (recordingDir + "/\(folderID)/joined/")).first else {
                let result = CDVPluginResult(
                    status: CDVCommandStatus_ERROR,
                    messageAs: ErrorCode.folderManipulationError.toDictionary(message: "contentsOfDirectory error")
                    )
                self.commandDelegate.send(result, callbackId: command.callbackId)
                return
            }
            audioPath = URL(fileURLWithPath: (recordingDir + "/\(folderID)/joined/\(j)"))
        } catch let err {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.folderManipulationError.toDictionary(message: "file manager error: \(err)")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: audioPath.absoluteString)
         self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // もう使わない
    @objc func getAudio(_ command: CDVInvokedUrlCommand) {
        guard let folderID = command.argument(at: 0) as? String else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: getAudio] First argument required. Please specify folder id")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        self.folderID = folderID
        
        do {
            guard let j = try FileManager.default.contentsOfDirectory(atPath: (recordingDir + "/\(folderID)/joined/")).first else {
                let result = CDVPluginResult(
                    status: CDVCommandStatus_ERROR,
                    messageAs: ErrorCode.folderManipulationError.toDictionary(message: "file manager error")
                    )
                self.commandDelegate.send(result, callbackId: command.callbackId)
                return
            }
            
            let audioPath = URL(fileURLWithPath: (recordingDir + "/\(folderID)/joined/\(j)"))
            let asset = AVURLAsset(url:audioPath)
            
            let joinedAudio = Audio(name: "joined_audio", duration: String(asset.duration.seconds), path: audioPath.absoluteString)
            
            let audio = RecordedAudio(audios: [], fullAudio: joinedAudio, folderID: folderID)
            
            // JSON データの形成
            let encoder = JSONEncoder()
            encoder.keyEncodingStrategy = .convertToSnakeCase // スネークケースに変換

            let data = try encoder.encode(audio)
            
            // Dictionary 型にキャスト
            let sendMessage = try JSONSerialization.jsonObject(with: data, options: []) as! [String: Any]
            
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: sendMessage)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        } catch let err {
            self.cordovaResultError(command, message: "get audio error: \(err)")
        }
    }
    
    // 録音中のものの波形を取得する
    @objc func getWaveForm(_ command: CDVInvokedUrlCommand) {
        guard let id = command.argument(at: 0) as? String,
            let joinedAudioPath = URL(string: id) else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: getWaveForm] First argument required. Please specify folder id")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        do {
            let pcmBufferPath = try getWaveForm(path: joinedAudioPath, tempPath: tempWaveFormPath)
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: pcmBufferPath.absoluteString)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        } catch let err {
            self.cordovaResultError(command, message: "get wave form error: \(err)")
        }
    }
    
    // 選択したファイルから波形を取得する
    @objc func getWaveFormByFile(_ command: CDVInvokedUrlCommand) {
        guard let id = command.argument(at: 0) as? String,
            let filePath = URL(string: id) else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: getWaveForm] First argument required. Please specify file path")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        
        do {
            let pcmBufferPath = try getWaveForm(path: filePath, tempPath: tempWaveFormPath)
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: pcmBufferPath.absoluteString)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        } catch let err {
            self.cordovaResultError(command, message: "get wave form error: \(err)")
        }
    }
    
    /**
        path: 波形取得対象のローカルファイル
        一時保存のディレクトリ
     */
    @objc func getWaveForm(path: URL, tempPath: String) throws -> URL  {
        // 音声ファイル読み込み
        let audioFile = try AVAudioFile(forReading: path)
        // 全てのフレーム数
        let nframe = Int(audioFile.length)
        var output: [Float] = []
        
        // 1ループごとに読み込むフレーム数
        let frameCapacity = AVAudioFrameCount(bufferSize)
        // 最後までループする
        while audioFile.framePosition < nframe {
            let PCMBuffer = AVAudioPCMBuffer(pcmFormat: audioFile.processingFormat, frameCapacity: frameCapacity)!
            // read すると framePosition が進む
            try audioFile.read(into: PCMBuffer, frameCount: PCMBuffer.frameCapacity)
            // 最大音量を配列に追加する
            output.append(getMaxVolume(buffer: PCMBuffer))
        }
        
        // ファイル書き込み
        let bufferData = Data(bytes: output, count: output.count * 4)
        let pcmBufferPath = URL(fileURLWithPath: tempPath)
        try bufferData.write(to: pcmBufferPath)
        return pcmBufferPath
    }
    // 挿入 (間に録音)
    @objc func splitAndStart(_ command: CDVInvokedUrlCommand) {
        guard let splitSeconds = command.argument(at: 0) as? NSNumber else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: splitAndStart] First argument required Number.")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        
        // 既にスタートしてたら エラーを返す
        if isRecording {
            self.cordovaResultError(command, message: "already starting")
            return
        }
        
        // joined.wav を 0 ~ splitSeconds と splitSeconds ~ 最後まで で2ファイルに分ける
        let joinedURL = URL(fileURLWithPath: joinedPath)
        let joinedAsset = AVURLAsset(url: joinedURL)
        // A, B, C を結合順として定義する
        // B は録音先のパス
        let pathA = audioListDir + "/1.wav"
        let pathB = audioListDir + "/2.wav"
        let pathC = audioListDir + "/3.wav"
        
        do {
            removeAudios()
            // 0.05 未満のときは trim せずにファイルの移動だけする
            if splitSeconds.doubleValue <= 0.05 {
                if FileManager.default.fileExists(atPath: joinedPath) {
                    try! FileManager.default.moveItem(atPath: joinedPath, toPath: pathC)
                }
            }
            else {
                try trim(input: joinedPath, output: pathA, start: 0, end: splitSeconds.doubleValue)
                try trim(input: joinedPath, output: pathC, start: splitSeconds.doubleValue, end: joinedAsset.duration.seconds)
            }
            
            if FileManager.default.fileExists(atPath: joinedPath) {
                try! FileManager.default.removeItem(atPath: joinedPath)
            }
            
            // 途中でキルされた場合に音声がバグらないようにちゃんと終了処理をする
            let notificationCenter = NotificationCenter.default
            notificationCenter.addObserver(
                self,
                selector: #selector(CDVRecorder.pauseRecordForNotification(notification:)),
                name: NSNotification.Name.UIApplicationWillTerminate,
                object: nil)
            
            startRecord(path: URL(string: pathB)!)
            
            isRecording = true
            
            // 問題なければ result
            let resultData = ["sampleRate": getInputFormat()?.sampleRate]
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: resultData as [AnyHashable : Any])
            self.commandDelegate.send(result, callbackId:command.callbackId)
        } catch let err {
            print(err)
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: err.localizedDescription
            )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
    }
    
    // 特定のファイルの音量を変更(filepath, file_id, db)
    @objc func changeDecibelForFile(_ command: CDVInvokedUrlCommand) {
        guard let params = command.argument(at: 0) as? [Any] else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "First argument required. Please specify [input: String, output_id: String, Number]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        guard let filePath = params[0] as? String,
              let input = URL(string: filePath) else {
            sendCordovaError(command: command, err: NSError(domain: "ファイルパスを文字列で指定してください", code: -1, userInfo: nil))
            return
        }
        guard let id = params[1] as? String else {
            sendCordovaError(command: command, err: NSError(domain: "IDを文字列で指定してください", code: -1, userInfo: nil))
            return
        }
        guard let db = params[2] as? NSNumber else {
            sendCordovaError(command: command, err: NSError(domain: "デシベルを数値で入力してください", code: -1, userInfo: nil))
            return
        }
        let outputPath = changeDecibelDir + "/\(id)"
        if !FileManager.default.fileExists(atPath: outputPath) {
            do {
                try changeDecibel(input: input.path, output: outputPath, db: db.doubleValue)
            }
            catch let err {
                sendCordovaError(command: command, err: err)
                return
            }
        }
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: URL(fileURLWithPath: outputPath).absoluteString)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // 選択範囲の音量を変更
    @objc func changeDecibel(_ command: CDVInvokedUrlCommand) {
        guard let params = command.argument(at: 0) as? [NSNumber] else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "First argument required. Please specify [number, ...]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        if params.count < 1 {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "First argument required. Please specify [number, ...]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        
        let db = params[0].doubleValue
        let outputPath = joinedPath
        let targetPath = joinedPath
        let audio = AVURLAsset(url: URL(fileURLWithPath: targetPath))
        removeAudios()
        // A, B, C を結合順として定義する
        // B は録音先のパス
        let pathA = audioListDir + "/1.wav"
        let pathB = audioListDir + "/2.wav"
        let pathC = audioListDir + "/3.wav"
        
        var audioData: [String : Any]? = nil
        
        if params.count <= 1 {
            do {
                try changeDecibel(input: targetPath, output: outputPath, db: db)
                audioData = getJoinedAudioData()
                let full_audio: [String: Any] = audioData?["full_audio"] as! [String : Any]
                let duration: Double = full_audio["duration"] as! Double
                audioData?.updateValue([
                    "duration": duration,
                    "start" : 0,
                    "end": duration,
                ], forKey: "updated_audio")
            }
            catch let err {
                sendCordovaError(command: command, err: err)
                return
            }
        }
        else {
            // 切り取りする
            let start = max(0, params[1].doubleValue)
            let end = min(params[2].doubleValue, audio.duration.seconds)
        
            do {
                if start == end {
                    throw NSError(domain: "選択範囲が狭すぎます", code: -1, userInfo: nil)
                }
                try trim(input: targetPath, output: pathB, start: start, end: end)
            }
            catch let err {
                sendCordovaError(command: command, err: err)
                return
            }
            
            do {
                // 範囲の音量を上げる
                try changeDecibel(input: pathB, output: pathB, db: db)
                // 範囲より前側を切り取り
                // start が 0.05 以上のときだけ trim
                if start >= 0.05 {
                    try trim(input: targetPath, output: pathA, start: 0, end: start)
                }
                // 範囲より後側を切り取り
                if end <= (audio.duration.seconds - 0.05) {
                    try trim(input: targetPath, output: pathC, start: end, end: audio.duration.seconds)
                }
                try FileManager.default.removeItem(atPath: joinedPath)
                
                let updatedAudioAsset = AVURLAsset(url: URL(fileURLWithPath: pathB))
                let duration = updatedAudioAsset.duration.seconds
                
                // 音声を結合
                let err = generateJoinedAudio()
                if err != nil {
                    throw NSError(domain: err!, code: -1, userInfo: nil)
                }
                audioData = getJoinedAudioData()
                audioData?.updateValue([
                    "duration": duration,
                    "start": start,
                    "end": start + duration,
                ], forKey: "updated_audio")
            }
            catch let err {
                sendCordovaError(command: command, err: err)
                return
            }
        }
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: audioData)
        self.commandDelegate.send(result, callbackId: command.callbackId)
        
    }
    
    // 指定した範囲の音声を生成する
    private func trim(input: String, output: String, start: Double, end: Double) throws {
        // Audio Asset 作成
        let audioURL = URL(fileURLWithPath: input)
        let audioAsset = AVURLAsset(url: audioURL)
        let semaphore = DispatchSemaphore(value: 0);
        
        // composition 作成
        let composition = AVMutableComposition()
        let audioAssetTrack = audioAsset.tracks(withMediaType: AVMediaType.audio).first
        if audioAssetTrack == nil {
            throw NSError(domain: "音声の読み込みに失敗しました。", code: -1, userInfo: nil)
        }
        guard let audioCompositionTrack = composition.addMutableTrack(withMediaType: AVMediaType.audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw NSError(domain: "音声の読み込みに失敗しました。", code: -2, userInfo: nil)
        }

        let timescale = Int32(NSEC_PER_SEC)
        let range = CMTimeRangeMake(CMTimeMakeWithSeconds(start, timescale), CMTimeMakeWithSeconds(end - start, timescale))
        
        try audioCompositionTrack.insertTimeRange(range, of: audioAssetTrack!, at: kCMTimeZero)
        removeTempWav()
        // 一時保存ファイルとして export 後, もとのファイルを削除してリネーム
        let cutFilePath = URL(fileURLWithPath: tempWavPath)
        let outputPath = URL(fileURLWithPath: output)
        var err: String?
        //  export
        if let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) {
            exportSession.outputFileType = AVFileType.wav
            exportSession.outputURL = cutFilePath
            
            exportSession.exportAsynchronously {
                switch exportSession.status {
                case .completed:
                    
                    // output 先にファイルが有れば削除
                    if FileManager.default.fileExists(atPath: outputPath.path) {
                        try! FileManager.default.removeItem( atPath: outputPath.path )
                    }
                    // ファイルを移動
                    try! FileManager.default.moveItem(atPath: cutFilePath.path, toPath: outputPath.path)
                    
                    semaphore.signal()
                case .failed, .cancelled:
                    err = "[join error: failed or cancelled]" + exportSession.error.debugDescription
                    print(err)
                    semaphore.signal()
                case .waiting:
                    print(exportSession.progress);
                default:
                    err = "[join error: other error]" + exportSession.error.debugDescription
                    print(err)
                    semaphore.signal()
                }
            }
        }
        
        semaphore.wait()
        
        if err != nil {
            throw NSError(domain: err!, code: -1, userInfo: nil)
        }
    }
    
    // 分割する
    @objc func trim(_ command: CDVInvokedUrlCommand) {
        guard let params = command.argument(at: 0) as? [NSNumber] else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: trim] First argument required. Please specify [number, number]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        if params.count < 2 {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: trim] First argument required. Please specify [number, number]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        do {
            try trim(input: joinedPath, output: joinedPath, start: params[0].doubleValue, end: params[1].doubleValue)
        } catch let err {
            self.cordovaResultError(command, message: err.localizedDescription)
            return
        }
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: getJoinedAudioData())
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // 途中を切り取る
    @objc func cut(_ command: CDVInvokedUrlCommand) {
        guard var params = command.argument(at: 0) as? [[NSNumber]] else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: cut] First argument required. Please specify [[number, number], ...]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        if params.count == 0 {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: cut] First argument required. Please specify [[number, number], ...]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        var trimParams: [[Double]] = [[0, 0]]
        params.sort { $0[0].doubleValue < $1[0].doubleValue }
        var i = 1
        for param in params {
            let start = param[0].doubleValue
            let end = param[1].doubleValue
            trimParams[i - 1][1] = start
            trimParams.append([end, end])
            i = i + 1
        }
        trimParams[i - 1][1] = AVURLAsset(url: URL(fileURLWithPath: joinedPath)).duration.seconds
        i = 1
        do {
            removeAudios()
            for param in trimParams {
                if (param[0] != param[1]) {
                    try trim(input: joinedPath, output: audioListDir + "/\(String(format: "%08d", i)).wav", start: param[0], end: param[1])
                    i = i + 1
                }
            }
            if FileManager.default.fileExists(atPath: joinedPath) {
                try FileManager.default.removeItem(atPath: joinedPath)
            }
        }
        catch let err {
            print(err)
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: err.localizedDescription
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        let err = generateJoinedAudio()
        if err != nil {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: err
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
        else {
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: getJoinedAudioData())
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
    }
    
    
    private func changeDecibel(input: String, output: String, db: Double) throws {
        let audioEngine = AVAudioEngine()
        
        let inputURL = URL(fileURLWithPath: input)
        removeTempWav()
        let outputURL = URL(fileURLWithPath: tempWavPath)
        let audioFile = try AVAudioFile(forReading: inputURL)
        let format = audioFile.processingFormat
        
        let playerNode = AVAudioPlayerNode()
        self.eq = AVAudioUnitEQ()
        
        // 増幅させる db を設定
        eq?.globalGain = Float(db)
        
        audioEngine.attach(playerNode)
        audioEngine.attach(eq)
        
        // 接続 (player → eq → engine.outputNode)
        audioEngine.connect(playerNode, to: eq, format: format)
        audioEngine.connect(eq, to: audioEngine.outputNode, format: format)
        
        playerNode.scheduleFile(audioFile, at: nil)
        // オフラインレンダリングを有効化 (音声を再生せずにファイルに出力可能にする)
        let maxFrames: AVAudioFrameCount = 4096
        try audioEngine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: maxFrames)
        
        
        // 書き込み先ファイルをつくる
        let outputFile = try AVAudioFile(forWriting: outputURL, settings: format.settings)
        
        // 出力を受け取るPCMBuffer用意
        // The output buffer to which the engine renders the processed data.
        let buffer = AVAudioPCMBuffer(pcmFormat: audioEngine.manualRenderingFormat, frameCapacity: audioEngine.manualRenderingMaximumFrameCount)!

        
        // 再生 (オフライン)
        try audioEngine.start()
        playerNode.play()
        
        
        while audioEngine.manualRenderingSampleTime < audioFile.length {
            do {
                let frameCount = audioFile.length - audioEngine.manualRenderingSampleTime
                let framesToRender = min(AVAudioFrameCount(frameCount), buffer.frameCapacity)
                
                let status = try audioEngine.renderOffline(framesToRender, to: buffer)
                
                switch status {
                    
                case .success:
                    // The data rendered successfully. Write it to the output file.
                    try outputFile.write(from: buffer)
                    
                case .insufficientDataFromInputNode:
                    // Applicable only when using the input node as one of the sources.
                    break
                    
                case .cannotDoInCurrentContext:
                    // The engine couldn't render in the current render call.
                    // Retry in the next iteration.
                    break
                    
                case .error:
                    // An error occurred while rendering the audio.
                    fatalError("The manual rendering failed.")
                }
            } catch {
                fatalError("The manual rendering failed: \(error).")
            }
        }

        // Stop the player node and engine.
        playerNode.stop()
        audioEngine.stop()
        self.eq = nil
        
        if FileManager.default.fileExists(atPath: output) {
            try FileManager.default.removeItem(atPath: output)
        }
        try FileManager.default.moveItem(atPath: tempWavPath, toPath: output)
    }
    
    // preview 用に生成した音声を返す
    @objc func previewDecibelChanged(_ command: CDVInvokedUrlCommand) {
        guard let params = command.argument(at: 0) as? [NSNumber] else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "First argument required. Please specify [number, ...]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        if params.count < 1 {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "First argument required. Please specify [number, ...]")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        
        let db = params[0].doubleValue
        let outputPath = tempAudioListDir + "/preview.wav"
        var targetPath = joinedPath
        let audio = AVURLAsset(url: URL(fileURLWithPath: targetPath))
        removeTempAudios()
        
        if params.count > 1 {
            // 切り取りする
            let start = max(0, params[1].doubleValue)
            let end = min(params[2].doubleValue, audio.duration.seconds)
        
            do {
                if start == end {
                    throw NSError(domain: "選択範囲が狭すぎます", code: -1, userInfo: nil)
                }
                targetPath = tempAudioListDir + "/preview_trim.wav"
                try trim(input: joinedPath, output: targetPath, start: start, end: end)
            }
            catch let err {
                sendCordovaError(command: command, err: err)
                return
            }
        }
        do {
            try changeDecibel(input: targetPath, output: outputPath, db: db)
        }
        catch let err {
            sendCordovaError(command: command, err: err)
            return
        }
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: URL(fileURLWithPath: outputPath).absoluteString)
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    @objc func exportWithCompression(_ command: CDVInvokedUrlCommand) {
        commandDelegate.run(inBackground: { [weak self] in
            guard let self = self else { return }
            let semaphore = DispatchSemaphore(value: 0)
            var complete = false
            
            let inputPath = URL(fileURLWithPath: self.joinedPath)
            let outputPath = URL(fileURLWithPath: self.compressionPath)
            
            // file があった場合は削除して作る
            if FileManager.default.fileExists(atPath: outputPath.path) {
                do {
                    try FileManager.default.removeItem(at: outputPath)
                } catch let err {
                    let result = CDVPluginResult(
                        status: CDVCommandStatus_ERROR,
                        messageAs: ErrorCode.folderManipulationError.toDictionary(message: "file manager remove item error: \(err)")
                        )
                    self.commandDelegate.send(result, callbackId: command.callbackId)
                    return
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
                    complete = true
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
            
            if complete {
                let asset = AVURLAsset(url: outputPath)
                let sendMessage = [
                    "audios": [],
                    "full_audio": [
                        "path": outputPath.absoluteString,
                        "duration": String(asset.duration.seconds),
                        "name": "joined_audio"
                    ],
                ] as [String : Any]
                let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: sendMessage)
                self.commandDelegate.send(result, callbackId: command.callbackId)
            }
            else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "compression failed")
                self.commandDelegate.send(result, callbackId: command.callbackId)
            }
        })
    }
    
    @objc func setOnChangeEarPhoneConnectedStatus(_ command: CDVInvokedUrlCommand) {
        guard let callbackId = command.callbackId else {return}
        changeConnectedEarPhoneStatusCallBackId = callbackId
        
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: ["isConnected": self.isConnectedHeadphones()])
//        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: ["isConnected": true])
        result?.keepCallback = true
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    @objc func setOnDownloadBgmProgress(_ command: CDVInvokedUrlCommand) {
        guard let callbackId = command.callbackId else {return}
        downloadBgmProgressCallbackId = callbackId
    }
    
    @objc func getSampleRate(_ command: CDVInvokedUrlCommand) {
        let resultData = ["sampleRate": getInputFormat()?.sampleRate]
        let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: resultData as [AnyHashable : Any])
        commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    private func removeFolder() -> Error? {
        removeAudios()
        if FileManager.default.fileExists(atPath: joinedPath) {
            try! FileManager.default.removeItem(atPath: joinedPath)
        }
        if FileManager.default.fileExists(atPath: compressionPath) {
            try! FileManager.default.removeItem(atPath: compressionPath)
        }
        return nil
    }
    
    private func isConnectedHeadphones() -> Bool {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        var flag = false
        for output in outputs {
            let portType = output.portType
            if  portType == AVAudioSessionPortHeadphones ||
                portType == AVAudioSessionPortBluetoothA2DP ||
                portType == AVAudioSessionPortBluetoothLE ||
                portType == AVAudioSessionPortBluetoothHFP
            {
                flag = true;
            }
        }
        return flag
    }
    
    
    // 1 チャンネルの最大音量を取得
    private func getMaxVolume(buffer: AVAudioPCMBuffer) -> Float {
        let data = buffer.floatChannelData![0]
        let length = Int(buffer.frameLength)
        return getMaxVolume(data: data, startIndex: 0, length: length, maxVolume: 0)
    }
    
    // 1 チャンネルの最大音量を取得。 startIndex ~ length までしか比較せず、初期値を指定できる
    private func getMaxVolume(data: UnsafeMutablePointer<Float>, startIndex: Int, length: Int, maxVolume: Float) -> Float {
        var maxVolume = maxVolume
        var n = startIndex
        while n < length {
            let volume = abs(data[n])
            if (volume > maxVolume) {
                maxVolume = volume
            }
            n += 1
        }
        return maxVolume
    }
    
    private func sendCordovaError(command: CDVInvokedUrlCommand, err: Error) {
        print(err)
        let result = CDVPluginResult(
            status: CDVCommandStatus_ERROR,
            messageAs: err.localizedDescription
        )
        self.commandDelegate.send(result, callbackId: command.callbackId)
    }
    
    // start private func
    private func startRecord(path: URL) {
        do {
            // audioSession をアクティブにする
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(AVAudioSessionCategoryPlayAndRecord,
                                               mode: AVAudioSessionModeDefault,
                                               options: [.allowBluetoothA2DP, .allowBluetooth, .allowAirPlay])
            try audioSession.setActive(true)
            
            // マイクのフォーマット
            let micFormat = self.getInputFormat()
            
            // audio file
            let audioFile = try! AVAudioFile(forWriting: path, settings: self.getInputSettings()!)
            
            // マイクのインストール
            engine?.inputNode.installTap(onBus: 0, bufferSize: UInt32(self.bufferSize), format: micFormat) {  [weak self] (buffer:AVAudioPCMBuffer, when:AVAudioTime) in
                guard let self = self else {return}
                self.pushBuffer(buffer: buffer)
                do {
                    try audioFile.write(from: buffer)
                } catch let err {
                    print("[cdv plugin REC: error]", err)
                }
            }
            
            // engine のスタートとストップは非同期処理しないとタイミングがおかしくなる
            DispatchQueue.main.async {
                do {
                    try self.engine?.start()
                    self.playBgm()
                    self.audioIndex += 1 // increment index
                 } catch let error {
                     print("[cdv plugin REC] engin start error", error)
                 }
            }
 
        } catch let error {
            print("[cdv plugin REC] Audio file error", error)
        }
    }
    
    // 録音をとめる
    private func pauseRecord() throws -> Bool {
        // 波形の余りがあれば送信する
        if volumeCheckCount > 0 {
            callPushBufferCallback()
            volumeCheckCount = 0
        }
        // stop engine
        self.pauseBgm() // bgm も停止
        self.engine?.stop()
        self.engine?.inputNode.removeTap(onBus: 0)
        do {
            // セッションを非アクティブ化
            try AVAudioSession.sharedInstance().setActive(false)
        } catch let err {
            // エラーハンドリング
            throw err
        }
        
        // 追加が終わったら true
        return true
    }
    
    // bufferSize ごとに maxVolume を Web に pushBufferCallback する
    private func pushBuffer(buffer: AVAudioPCMBuffer) {
        var checkCount = bufferSize - volumeCheckCount
        let length = Int(buffer.frameLength)
        let data = buffer.floatChannelData![0]
        
        var startIndex = 0
        // bufferSize が足りる限り maxVolume を Web へ送信
        while checkCount <= length - startIndex {
            maxVolume = getMaxVolume(data: data, startIndex: startIndex, length: checkCount, maxVolume: maxVolume)
            callPushBufferCallback()
            startIndex += checkCount
            checkCount = bufferSize
        }
        // 余った分を次のループのために一時変数に保存
        volumeCheckCount = length - startIndex
        if volumeCheckCount > 0 {
            maxVolume = getMaxVolume(data: data, startIndex: startIndex, length: length, maxVolume: maxVolume)
        }
    }
    
    // pushBufferのcallbackを実行する
    private func callPushBufferCallback() {
        // call back が登録されていたら
        if self.pushBufferCallBackId != nil {
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: [maxVolume])
            result?.keepCallback = true
            self.commandDelegate.send(result, callbackId: self.pushBufferCallBackId)
        }
        maxVolume = 0
        volumeCheckCount = 0
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
        var nextStartTime = kCMTimeZero
        var result: Audio?
        let composition = AVMutableComposition()
        let track = composition.addMutableTrack(withMediaType: AVMediaType.audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        let semaphore = DispatchSemaphore(value: 0)
        
        let joinedFilePath = URL(fileURLWithPath: recordingDir + "/\(folderID)/joined/joined.wav", isDirectory: false)
        let isJoinedFile = FileManager.default.fileExists(atPath: joinedFilePath.path);
        var audio_files:[String] = [];
        

        let audioFolderPath = recordingDir + "/\(folderID)/divided"
        
        if isJoinedFile {
            audio_files.append(joinedFilePath.absoluteString)
        }
        
        for audio in audio_files {
            let fullPath = URL(string: audio)!
            if FileManager.default.fileExists(atPath:  fullPath.path) {
                let asset = AVURLAsset(url: fullPath)
                if let assetTrack = asset.tracks.first {
                    let timeRange = CMTimeRange(start: kCMTimeZero, duration: asset.duration)
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
            
            exportSession.exportAsynchronously(completionHandler: { [weak self] in
                guard let self = self else { return }
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
                    
                    
                    let asset = AVURLAsset(url: joinedFolder);
                    
                    result = Audio(name:"joined_audio", duration: String(asset.duration.seconds), path: joinedFolder.absoluteString)
                    
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
    

        
    // for debug alert
    private func debugAlert(message: String) {
        DispatchQueue.main.sync {
            let alert: UIAlertController = UIAlertController(title: "debug", message: message, preferredStyle:  UIAlertControllerStyle.alert)
            let defaultAction: UIAlertAction = UIAlertAction(title: "OK", style: UIAlertActionStyle.default, handler:{
                (action: UIAlertAction!) -> Void in
                print("OK")
            })
            alert.addAction(defaultAction)
            self.viewController.present(alert, animated: true, completion: nil)
        }

    }
    
    private func getInputFormat() -> AVAudioFormat? {
        guard let engine = self.engine else {return nil}
        return engine.inputNode.inputFormat(forBus: 0)
    }
    
    private func getInputSettings() -> [String: Any]? {
        guard let format = self.getInputFormat() else {return nil}
        return format.settings
    }
    
}

// for BGM
extension CDVRecorder {
    // BGM だけ再生する
    @objc func playBgm(_ command: CDVInvokedUrlCommand)  {
        playBgm()
    }
    
    // BGM だけ停止する
    @objc func pauseBgm(_ command: CDVInvokedUrlCommand)  {
        pauseBgm()
    }
    
    // BGM をセットする
    @objc func setBgm(_ command: CDVInvokedUrlCommand)  {
        let value = command.argument(at: 0) as? [String: Any]
        guard
            let engine = self.engine,
            let audioMixer = self.audioMixer,
            let val = value,
            let name = val["name"] as? String,
            let url = val["url"] as? String,
            let loop = val["loop"] as? Bool,
            let volume = val["volume"] as? Float else {return}
        
        let seek = value?["seek"] as? Double
        do {
            // エンジンに BGM を付与
            let audioPlayerNode = AVAudioPlayerNode()
            engine.attach(audioPlayerNode)
            engine.connect(audioPlayerNode, to: audioMixer, format: nil)
            engine.connect(audioMixer, to: engine.mainMixerNode, format: nil)
            // BGMを作成
            let bgm = CDVRecorderBgm(name: name ,urls: [url], loop: loop, volume: volume, seek: seek, playerNode: audioPlayerNode)
            // BGM を追加
            bgms.append(bgm)
            
            // cordova に報告
            self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: "set"), callbackId: command.callbackId)
        }
        catch let error {
            self.commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: error.localizedDescription), callbackId: command.callbackId)
        }
    }
    
    @objc func hoge(_ notification: Notification) {
        print(notification)
    }
    
    // 指定の秒数までBGMを動かす
    @objc func seekBgm(_ command: CDVInvokedUrlCommand) {
        guard let s = command.argument(at: 0) as? NSNumber else {
            let result = CDVPluginResult(
                status: CDVCommandStatus_ERROR,
                messageAs: ErrorCode.argumentError.toDictionary(message: "[recorder: seekBgm] First argument required. Please specify number")
                )
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        let seconds = s.floatValue
        seekBgm(time: Double(seconds))
        commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true), callbackId: command.callbackId)
    }
    // 0秒まで戻る
    @objc func resetBgmTime(_ command: CDVInvokedUrlCommand) {
        seekBgm(time: Double(0.0))
        commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true), callbackId: command.callbackId)
    }
    
    @objc func clearBgm(_ command: CDVInvokedUrlCommand)  {
        // プレイヤーをストップして、全ての BGM を削除する
        bgms.forEach({ bgm in
            bgm.pause()
//            bgm.player.removeAllItems()
        })
        bgms = []
        commandDelegate.send(CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true), callbackId: command.callbackId)
    }

    @objc func listBgm(_ command: CDVInvokedUrlCommand)  {
        
    }
    // ダウンロードする
    @objc func downloadBgm(_ command: CDVInvokedUrlCommand) {
        // ダウンロードしたものを配置するフォルダがなければ作成する
        let baseBgmDownloadPath = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first! + "/bgms"
        let bgmBaseDownloadURL = URL(fileURLWithPath: baseBgmDownloadPath)
        if !FileManager.default.fileExists(atPath: bgmBaseDownloadURL.path) {
            do {
                try FileManager.default.createDirectory(at: bgmBaseDownloadURL, withIntermediateDirectories: true)
            } catch {
                
                let result = CDVPluginResult(
                    status: CDVCommandStatus_ERROR,
                    messageAs: ErrorCode.folderManipulationError.toDictionary(message: "can't create bgm folder")
                    )
                self.commandDelegate.send(result, callbackId: command.callbackId)
            }
        }
        
        // ダウンロード開始
        let notDownloads = bgms.filter({ return !$0.isDownload})
        //
        if notDownloads.count == 0 {
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
            self.commandDelegate.send(result, callbackId: command.callbackId)
            return
        }
        var progresses: [Double] = []
        for (index, item) in notDownloads.enumerated() {
            // download URL
            let url = item.urls[0]
            // 保存先
            let path = bgmBaseDownloadURL.appendingPathComponent(item.name)
            let dest:DownloadRequest.Destination = { _, _ in
                return (path, [.removePreviousFile, .createIntermediateDirectories])
            }
            progresses.append(0.0)
            AF.download(url, to: dest).downloadProgress { p in
                progresses[index] = p.fractionCompleted
                let sum = progresses.reduce(0.0) {$0 + $1}
                if self.downloadBgmProgressCallbackId != nil {
                    let data = ["total": Double(progresses.count), "progress": sum]
                    let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: data)
                    self.commandDelegate.send(result, callbackId: self.downloadBgmProgressCallbackId)
                }

                // すべてのダウンロードが完了した
                if (Double(progresses.count) == sum) {
                    let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: true)
                    self.commandDelegate.send(result, callbackId: command.callbackId)
                }
            }
            .responseData { response in
                // 一つずつのデータのダウンロードが完了した
                item.path = path
                item.audioFile = try! AVAudioFile(forReading: path)
                item.isDownload = true
                
            }
        }
        
        
        
    }
    // play する
    private func playBgm() {
        bgms.forEach({bgm in
            bgm.play();
        });
    }
    // pause する
    private func pauseBgm() {
        bgms.forEach({bgm in
            bgm.pause();
        });
    }
    // シークする
    private func seekBgm(time: Double) {
        bgms.forEach({bgm in
            bgm.seek(position: time)
        });
    }
    // BGMが再生可能か？
    private func canPlayBgm() -> Bool {
        return bgms.allSatisfy({
            
            $0.status == "canPlay"
            
        })
    }
    // ミュートにする
    private func muteBgm() {
        bgms.forEach({bgm in
            bgm.mute()
        });
    }
    // ミュート解除
    private func resignMuteBgm() {
        bgms.forEach({bgm in
            bgm.resignMute()
        });
    }
}

// for mic or head set
extension CDVRecorder {
    // ヘッドフォンが装着されたら
    @objc private func handleAudioRouteChange(notification: Notification) {
        guard let userInfo = notification.userInfo,
            let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue:reasonValue) else {
                return
        }
        switch reason {
            case .newDeviceAvailable:
                let session = AVAudioSession.sharedInstance()
                for output in session.currentRoute.outputs {
                    let portType = output.portType
                    if  portType == AVAudioSessionPortHeadphones ||
                        portType == AVAudioSessionPortBluetoothA2DP ||
                        portType == AVAudioSessionPortBluetoothLE ||
                        portType == AVAudioSessionPortBluetoothHFP
                    {
                        headphonesConnected = true
                        if (self.isRecording) {
                            DispatchQueue.main.async {
                                do {
                                    try self.engine?.start()
                                    self.playBgm()
                                }
                                catch {
                                    return
                                }
                            }
                        }
                        if (changeConnectedEarPhoneStatusCallBackId != nil) {
                            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: ["isConnected": true])
                            result?.keepCallback = true
                            commandDelegate.send(result, callbackId: changeConnectedEarPhoneStatusCallBackId)
                        }
                        print("headphone plugged in")
                    }
                    break
                }
            case .oldDeviceUnavailable:
                if let previousRoute =
                    userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription {
                    for output in previousRoute.outputs {
                        let portType = output.portType
                        if  portType == AVAudioSessionPortHeadphones ||
                            portType == AVAudioSessionPortBluetoothA2DP ||
                            portType == AVAudioSessionPortBluetoothLE ||
                            portType == AVAudioSessionPortBluetoothHFP
                        {
                            headphonesConnected = false
                            if (self.isRecording) {
                                DispatchQueue.main.async {
                                    do {
                                        try self.engine?.start()
                                        self.playBgm()
                                    }
                                    catch {
                                        return
                                    }
                                }
                            }
                            if (changeConnectedEarPhoneStatusCallBackId != nil) {
                                let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: ["isConnected": false])
                                result?.keepCallback = true
                                commandDelegate.send(result, callbackId: changeConnectedEarPhoneStatusCallBackId)
                            }
                            print("headphone pulled out")
                        }

                        break
                    }
                }
            default: ()
        }
    }
    
}
