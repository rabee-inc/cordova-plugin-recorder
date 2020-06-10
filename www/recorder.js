'use strict';

var exec = require("cordova/exec");


// cordova の実行ファイルを登録する
const registerCordovaExecuter = (action, onSuccess, onFail, param) => {
  return exec(onSuccess, onFail, 'Recorder', action, [param]);
};

// promise で返す。 cordova の excuter の wrapper
const createAction = (action, params) => {
  return new Promise((resolve, reject) => {
      // actionが定義されているかを判定したい
      if (true) {
          // cordova 実行ファイルを登録
          registerCordovaExecuter(action, resolve, reject, params);
      }
      else {
          // TODO: error handling
      }
  });
};

// Recorder 本体
const recorder = {
  // bgm namespace
  bgm: {
    set: (params) => {
      const {url, loop} = params
      // url は一つ以上欲しい
      if (!url) {
        return Promise.reject('Please set at least one bgm url resource')
      }

      return createAction('setBgm', params)
    },
    list: (params) => createAction('getBgmList', params),
    clear: (params) => createAction('clearBgm', params),
    play: (params) => createAction('playBgm', params),
    pause: (params) => createAction('pauseBgm', params),
    seek: (params) => createAction('seekBgm', params),
    resetTime: (params) => createAction('resetBgmTime', params),
    download: (params) => createAction('downloadBgm', params),
    onDownloadProgress: (params) => createAction('setOnDownloadBgmProgress', params)
  },
  initialize: (params) => createAction('initialize', params),
  start: (params) => createAction('start', params),
  pause: (params) => createAction('pause', params),
  resume: (params) => createAction('resume', params),
  stop: (params) => createAction('stop', params),
  getRecordingFolders: (params) => createAction('getRecordingFolders', params),
  removeCurrentFolder: (params) => createAction('removeCurrentFolder', params),
  removeFolder: (params) => createAction('removeFolder', params),
  export: (params) => createAction('export', params),
  exportWithCompression: (params) => createAction('exportWithCompression', params),
  getWaveForm: (params) => createAction('getWaveForm', params),
  initSettings: (params) => createAction('initSettings', params),
  exportWithCompression: (params) => createAction('exportWithCompression', params),
  split: (params) => createAction('split', params),
  getAudio: (params) => createAction('getAudio', params),
  getSampleRate: (params) => createAction('getSampleRate', params),
  // イベントリスナー系
  onPushBuffer: (callback, onFail, param) => {
    return exec(callback, onFail, "Recorder", "onPushBuffer", [param]);
  },
  onProgressCompression: (callback, onFail, param) => {
    return exec(callback, onFail, "Recorder", "onProgressCompression", [
      param
    ]);
  },
  onChangeEarPhoneConnectedStatus: (callback, onFail, param) => {
    return exec(callback, onFail, "Recorder", "setOnChangeEarPhoneConnectedStatus", [
      param
    ]);
  },
};

module.exports = recorder;
