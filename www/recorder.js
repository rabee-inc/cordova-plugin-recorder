'use strict';

var exec = require("cordova/exec");

// cordova exec
var _Recorder = {
  initialize: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "initialize", [param]);
  },
  start: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "start", [param]);
  },
  pause: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "pause", [param]);
  },
  resume: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "resume", [param]);
  },
  stop: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "stop", [param]);
  },
  getRecordingFolders: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "getRecordingFolders", [
      param
    ]);
  },
  removeCurrentFolder: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "removeCurrentFolder", [
      param
    ]);
  },
  removeFolder: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "removeFolder", [param]);
  },
  setFolder: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "setFolder", [param]);
  },
  export: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "export", [param]);
  },
  exportWithCompression: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "exportWithCompression", [
      param
    ]);
  },
  getWaveForm: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "getWaveForm", [param]);
  },
  initSettings: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "initSettings", [param]);
  },
  split: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "split", [param]);
  },
  getAudio: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, "Recorder", "getAudio", [param]);
  }
};

// promise wrapper
var Recorder = {
  initialize: params => {
    return new Promise((resolve, reject) => {
      _Recorder.initialize(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  start: params => {
    return new Promise((resolve, reject) => {
      _Recorder.start(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  pause: params => {
    return new Promise((resolve, reject) => {
      _Recorder.pause(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  resume: params => {
    return new Promise((resolve, reject) => {
      _Recorder.resume(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  stop: params => {
    return new Promise((resolve, reject) => {
      _Recorder.stop(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  export: params => {
    return new Promise((resolve, reject) => {
      _Recorder.export(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  exportWithCompression: params => {
    return new Promise((resolve, reject) => {
      _Recorder.exportWithCompression(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  onPushBuffer: (callback, onFail, param) => {
    return exec(callback, onFail, "Recorder", "onPushBuffer", [param]);
  },
  onProgressCompression: (callback, onFail, param) => {
    return exec(callback, onFail, "Recorder", "onProgressCompression", [
      param
    ]);
  },
  getRecordingFolders: params => {
    return new Promise((resolve, reject) => {
      _Recorder.getRecordingFolders(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  removeCurrentFolder: params => {
    return new Promise((resolve, reject) => {
      _Recorder.removeCurrentFolder(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  removeFolder: params => {
    return new Promise((resolve, reject) => {
      _Recorder.removeFolder(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  setFolder: params => {
    return new Promise((resolve, reject) => {
      _Recorder.setFolder(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  getWaveForm: params => {
    return new Promise((resolve, reject) => {
      _Recorder.getWaveForm(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  initSettings: params => {
    return new Promise((resolve, reject) => {
      _Recorder.initSettings(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  split: params => {
    return new Promise((resolve, reject) => {
      _Recorder.split(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  },
  getAudio: params => {
    return new Promise((resolve, reject) => {
      _Recorder.getAudio(
        res => {
          resolve(res);
        },
        err => {
          reject(err);
        },
        params
      );
    });
  }
};

module.exports = Recorder;
