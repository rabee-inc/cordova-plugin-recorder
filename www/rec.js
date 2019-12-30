'use strict';

var exec = require('cordova/exec');

// cordova exec
var _Rec = {
  start: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'start', [param]);
  },
  pause: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'pause', [param]);
  },
  resume: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'resume', [param]);
  },
  stop: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'stop', [param]);
  },
  getRecordingFolders: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'getRecordingFolders', [param]);
  },
  removeCurrentFolder: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'removeCurrentFolder', [param]);
  },
  removeFolder: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'removeFolder', [param]);
  },
  setFolder: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'setFolder', [param]);
  },
  export: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'export', [param]);
  },
  exportWithCompression: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'exportWithCompression', [param]);
  },
  getWaveForm: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'getWaveForm', [param]);
  },
  initSettings: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'initSettings', [param]);
  },
  split: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'split', [param]);
  },
  getAudio: (onSuccess, onFail, param) => {
    return exec(onSuccess, onFail, 'Rec', 'getAudio', [param]);
  }
};

// promise wrapper
var Rec = {
  start: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.start((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  pause: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.pause((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  resume: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.resume((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  stop: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.stop((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  export: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.export((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  exportWithCompression: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.exportWithCompression((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  onPushBuffer: (callback, onFail, param) => {
    return exec(callback, onFail, 'Rec', 'onPushBuffer', [param]);
  },
  onProgressCompression: (callback, onFail, param) => {
    return exec(callback, onFail, 'Rec', 'onProgressCompression', [param]);
  },
  getRecordingFolders: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.getRecordingFolders((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  removeCurrentFolder: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.removeCurrentFolder((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  removeFolder: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.removeFolder((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  setFolder: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.setFolder((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  getWaveForm: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.getWaveForm((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  initSettings: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.initSettings((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  split: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.split((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  },
  getAudio: (params) => {
    return new Promise((resolve, reject) => {
      _Rec.getAudio((res) => {
        resolve(res);
      }, (err) => {
        reject(err);
      }, params);
    });
  }
}

module.exports = Rec;
  
