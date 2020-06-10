//
//  CDVRecorderBgm.swift
//
//  Created by shogo on 2020/06/06.
//

import Foundation
import AVFoundation

class CDVRecorderBgm {
    var name: String
    var urls: [String]
    var loop: Bool
    var volume: Float = 1.0
    var playerNode: AVAudioPlayerNode
    var status: String = "wating"
    var isDownload: Bool = false
    var audioFile: AVAudioFile?
    var path: URL?
    var offset = 0.0
    
    init(name: String, urls: [String], loop: Bool, volume: Float?, seek: Double?, playerNode: AVAudioPlayerNode) {
        self.name = name
        self.urls = urls
        self.loop = loop
        self.playerNode = playerNode
        
        if let offset = seek {
            self.offset = offset
        }
        if let volume = volume {
            self.volume = volume
            self.playerNode.volume = self.volume
        }
        // 通知センター登録
        NotificationCenter.default.addObserver(self, selector: #selector(self.handleAudioRouteChange(notification:)), name: NSNotification.Name.AVAudioSessionRouteChange, object: nil)

    }
    
    // 再開
    func play() {
        guard let audioFile = audioFile else {return}
        // スケジュール設定
        if #available(iOS 11.0, *) {
            let sampleRate = audioFile.processingFormat.sampleRate
            let newSampleTime = AVAudioFramePosition(sampleRate * self.offset)
            let remainingLength = (Double(audioFile.length) / sampleRate) - self.offset
            let framesToPlay = AVAudioFrameCount(Double(remainingLength) * sampleRate)
            playerNode.stop()
            playerNode.scheduleSegment(audioFile, startingFrame: newSampleTime, frameCount: framesToPlay, at: nil, completionHandler: nil)
            
            // ループの時に利用するが、一旦コメントアウト
//            playerNode.scheduleFile(audioFile , at: nil, completionCallbackType:.dataPlayedBack ,completionHandler: { _ in
         
//                if (self.loop) {
//                    self.play()
//                }
//                else {
//                    self.playerNode.pause()
//                    self.status = "end"
//                }
//            })
            
            
            // ヘッドフォンをつけている場合
            if (self.isConnectedHeadphones()) {
                self.resignMute() // ミュート解除
            }
            // ヘッドフォンをつけていない場合
            else {
                self.mute() // ミュート
            }
            // 再生開始
            playerNode.play()
        }

    }
    
    // 停止
    func pause() {

        guard let audioFile = audioFile else {return}
        let sampleRate = audioFile.processingFormat.sampleRate
        if let nodeTime =  playerNode.lastRenderTime {
            let playerTime = playerNode.playerTime(forNodeTime: nodeTime)
            let currentTime = (Double(playerTime!.sampleTime) / sampleRate) + self.offset
            self.offset = currentTime
            print(self.offset)
        }

//        if (playerNode.isPlaying) {
            playerNode.stop()
//        }

    }
    
    func resume() {}
    
    // シーク (秒)
    func seek(position: Double) {
        seekWithLoop(position: position)
    }
    
    // ミュート
    func mute() {
        playerNode.volume = 0.0;
    }
    // ミュート戻す
    func resignMute() {
        playerNode.volume = volume
    }
    
    // ループを考慮したシーク
    private func seekWithLoop(position: Double) {
        guard let audioFile = audioFile else {return}
        // サンプルレート
        let sampleRate = audioFile.processingFormat.sampleRate
        // 再生時間
        let duration = Double(audioFile.length) / sampleRate
        
        var positionToSeek = position
        
        // 再生時間を超えていた場合
        if position > duration {
            if (loop) {
                positionToSeek = position - duration
            }
            else {
                positionToSeek = duration
            }
        }
        // シークさせる
        seekTime(position: positionToSeek)
        
    }
    
    // 単純なシーク
    private func seekTime(position: Double) {
        self.offset = position
        print("===============================> \(self.offset)")
        
    }
    
    
    deinit {

    }
}

// for mic or head set
extension CDVRecorderBgm {
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
                        self.resignMute()
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
                            self.mute()
                            print("headphone pulled out")
                        }

                        break
                    }
                }
            default: ()
        }
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
    
}

