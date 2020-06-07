/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Wait for the deviceready event before using any of Cordova's device APIs.
// See https://cordova.apache.org/docs/en/latest/cordova/events/events.html#deviceready
document.addEventListener('deviceready', onDeviceReady, false);
function onDeviceReady() {   
    // button
    const button1 = document.querySelector('.button1') 
    const button2 = document.querySelector('.button2') 
    const button3 = document.querySelector('.button3') 
    
    button1.addEventListener('click', play);
    button2.addEventListener('click', pause);
    button3.addEventListener('click', resume);

    Recorder.initialize().then(() => {
        Recorder.onChangeEarPhoneConnectedStatus((res) => {
            const message = res.isConnected ? 'イヤフォンが接続されました' : 'イヤフォンが外されました'
            window.alert(message)
        }, () => {}, {})
    });
}

async function play() {
    const recorder = window.recorder;
    const promises = [];
    // bgm 1つ目
    promises.push(recorder.bgm.set({
        name: 'hoge.m4a',
        loop: false,
        url: "https://storage.googleapis.com/staging-rec-rabee-jp.appspot.com/bgm%2Fv2%2Fsampo.m4a?GoogleAccessId=firebase-adminsdk-sgf4g%40staging-rec-rabee-jp.iam.gserviceaccount.com&Expires=16446985200&Signature=uR78ug9vDE8yEBameae6vJLB%2BZIkjTVhoEL1GPmRYcZgKH32BCiQgiktK2I21P212LwrHDFWGUKeuT8Zs3XQBY%2Bc6xLOpYHfeMfLYA0UBCNRx87QO%2FZX0P6NID6WhGXP4%2FIlkp%2Bj5t%2BhLnhjOOkmXGP52ote7epMTL3uCA5xDVU3kKOWjJ2xjqySK0JqU8z%2B73uumuTu1VD313ahwuXkJ6iMT9YOUPr1LwDB53FD5lDbN5biTC4FedGWawQ5B6ZXi5kJybuY0VEBi2Kxb35xOwqgUR8wTo0pL5bXQx4YrJZZ27vcyZRSwukXa0UaKTGhL0brZuQse%2F7h267U8TFzYw%3D%3D",
        volume: 1.0,
    }));
    // bgm 2つ目
    promises.push(recorder.bgm.set({
        name: 'fuga.m4a ',
        loop: true,
        url: "https://storage.googleapis.com/staging-rec-rabee-jp.appspot.com/bgm%2Fv2%2Fsamba.m4a?GoogleAccessId=firebase-adminsdk-sgf4g%40staging-rec-rabee-jp.iam.gserviceaccount.com&Expires=16446985200&Signature=Lw3BHCPrl3cgc85usOzci8PSefjSMFYXB%2FiQr1piwIQg3qNZ5Uu3b2TLd%2Bw214j76VPW076Jxbwcna5obaNbKz57mf3IyJLCVxs3Gi99ev2CsJrnc5TiU2cUp2rEoq47%2FV6UUx8vHlrTSIRWsV9kyTW%2BObZ9g5IezsBmi25JR%2FD%2Byf%2BlEBo55DKzUtd7M1ZPsicMfbCwxupsqEmvacGn9q6Ny9ZZIHh1QVT%2B1f4waVNrY2VI7HZWYN3ys6mXTrShZoNKWHzYdrez8208NMbXnDZuVqrlzhsc35m6CwVUosRADaoMEp0Ob%2F8Z%2B1LvLJ4RVPZmShF3ZUGRHrmynzuuAw%3D%3D",
        volume: 1.0,
    }));

    await Promise.all(promises);

    // bgm のダウンロードの進捗
    recorder.bgm.onDownloadProgress(() => {
        console.log('ダウンロード中')
    });
    // ダウンロードスタート
    await recorder.bgm.download().then(() => {
        window.alert('ダウンロードが完了しました。再生を開始します');
        recorder.start();
    }); 
}

function pause() {
    const recorder = window.recorder;
    recorder.pause();
}

function resume() {
    const recorder = window.recorder;
    recorder.resume();
}
