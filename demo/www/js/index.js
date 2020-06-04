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
    button1.addEventListener('click', hello);
}

function hello() {

    const recorder = window.recorder;

    // イヤフォンを付けてようと付けてまいと BGM をセットすることはできるし、録音することはできる

    // bgm grounp 1
    const bgm1_1 = 'https://hogehoeg.com/bgm1_1.mp3'
    const bgm1_2 = 'https://hogehoeg.com/bgm1_2.mp3'
    
    // bgm grounp 2
    const bgm2_1 = 'https://hogehoeg.com/bgm2_1.mp3'
    const bgm2_2 = 'https://hogehoeg.com/bgm2_1.mp3'

    // bgm grouop 1
    recorder.bgm.set({
        urls: [bgm1_1, bgm1_2],
        loop: true, // loop を true にするとセットされた bgm がループされる
    });

    // bgm grouop 2
    recorder.bgm.set({
        urls: [bgm2_1, bgm2_2],
        loop: false, // loop を false にするとセットされた bgm がループされない
    });

    recorder.bgm.clear();

    recorder.isEearPhone()


    // earphone のステータスが変わると発火する
    // earphone の抜き差しを検知して、録音を止めるか？
    recorder.onChangeEarPhoneStatus(({}) => {

    });



    recorder.start();

    window.recorder.add(addRequestParams).then((v) => {
        window.alert(v)
    });
}


function add() {  
}
