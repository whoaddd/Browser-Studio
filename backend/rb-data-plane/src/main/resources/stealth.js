// 反自动化检测脚本：在每个新文档的最早阶段执行（Page.addScriptToEvaluateOnNewDocument）。
// 目标：抹平“容器内 Chromium + CDP 自动化”与“真实 Windows Chrome 131 用户”之间的差异。
// 注意：所有改动都必须幂等且不影响页面正常运行，任何异常都不能向外抛出。
(function () {
  'use strict';
  try {
    if (window.__rbStealthInstalled) return;
    Object.defineProperty(window, '__rbStealthInstalled', { value: true, enumerable: false });

    var nativeToString = Function.prototype.toString;
    var patchedFns = new WeakSet();
    function mark(fn) {
      try { patchedFns.add(fn); } catch (e) {}
      return fn;
    }
    Function.prototype.toString = mark(function toString() {
      if (patchedFns.has(this)) {
        return 'function ' + (this.name || '') + '() { [native code] }';
      }
      return nativeToString.call(this);
    });

    function getter(value) {
      return mark(function () { return value; });
    }
    function define(obj, prop, value) {
      try {
        Object.defineProperty(obj, prop, {
          get: getter(value), set: undefined, configurable: true, enumerable: true
        });
      } catch (e) {}
    }

    var proto = Object.getPrototypeOf(navigator) || Navigator.prototype;

    // 1. navigator.webdriver —— 真实用户在自动化关闭时为 false
    try { delete Navigator.prototype.webdriver; } catch (e) {}
    try { delete proto.webdriver; } catch (e) {}
    define(proto, 'webdriver', false);

    // 2. chrome 对象（真实 Chrome 一定存在 runtime / app / csi / loadTimes）
    try {
      if (!window.chrome) window.chrome = {};
      if (!window.chrome.runtime) window.chrome.runtime = {};
      if (!window.chrome.app) {
        window.chrome.app = {
          isInstalled: false,
          InstallState: { DISABLED: 'disabled', INSTALLED: 'installed', NOT_INSTALLED: 'not_installed' },
          RunningState: { CANNOT_RUN: 'cannot_run', READY_TO_RUN: 'ready_to_run', RUNNING: 'running' },
          getDetails: mark(function () { return null; }),
          getIsInstalled: mark(function () { return false; }),
          runningState: mark(function () { return 'cannot_run'; })
        };
      }
      if (!window.chrome.csi) {
        window.chrome.csi = mark(function () {
          return { onLoadT: Date.now(), startE: Date.now(), pageT: 0, tran: 15 };
        });
      }
      if (!window.chrome.loadTimes) {
        window.chrome.loadTimes = mark(function () {
          var t = Date.now() / 1000;
          return {
            committedLoadTime: t, finishLoadTime: t, firstPaintTime: t, firstPaintAfterLoadTime: 0,
            navigationType: 'other', requestTime: t, startLoadTime: t, wasAlternateProtocolAvailable: false,
            wasFetchedViaSpdy: true, wasNpnNegotiated: true, npnNegotiatedProtocol: 'h2',
            connectionInfo: 'h2'
          };
        });
      }
    } catch (e) {}

    // 3. 权限查询（无头环境常返回 denied / 不存在）
    try {
      if (window.Notification) {
        Object.defineProperty(Notification, 'permission', { get: getter('default'), configurable: true });
        Object.defineProperty(Notification, 'maxActions', { get: getter(2), configurable: true });
      }
      var origQuery = navigator.permissions && navigator.permissions.query;
      if (origQuery) {
        navigator.permissions.query = mark(function query(desc) {
          if (desc && desc.name === 'notifications') {
            return Promise.resolve({ state: 'default', onchange: null });
          }
          return origQuery.apply(navigator.permissions, arguments);
        });
      }
    } catch (e) {}

    // 4. plugins / mimeTypes（构造带 item/namedItem/refresh 的类 PluginArray 对象）
    try {
      var pdfPlugin = {
        name: 'PDF Viewer',
        filename: 'internal-pdf-viewer',
        description: 'Portable Document Format',
        length: 1,
        item: mark(function (i) { return i === 0 ? pdfMime : null; }),
        namedItem: mark(function (n) { return n === 'application/pdf' ? pdfMime : null; }),
        __proto__: Plugin.prototype
      };
      var pdfMime = {
        type: 'application/pdf',
        suffixes: 'pdf',
        description: 'Portable Document Format',
        enabledPlugin: pdfPlugin,
        length: 1,
        item: mark(function () { return null; }),
        namedItem: mark(function () { return null; }),
        __proto__: MimeType.prototype
      };
      var plugins = [pdfPlugin];
      plugins.item = mark(function (i) { return this[i] || null; });
      plugins.namedItem = mark(function (n) {
        for (var i = 0; i < this.length; i++) if (this[i].name === n) return this[i];
        return null;
      });
      plugins.refresh = mark(function () {});
      plugins.__proto__ = PluginArray.prototype;

      var mimes = [pdfMime];
      mimes.item = mark(function (i) { return this[i] || null; });
      mimes.namedItem = mark(function (n) {
        for (var i = 0; i < this.length; i++) if (this[i].type === n) return this[i];
        return null;
      });
      mimes.__proto__ = MimeTypeArray.prototype;

      define(proto, 'plugins', plugins);
      define(proto, 'mimeTypes', mimes);
      define(proto, 'pdfViewerEnabled', true);
    } catch (e) {}

    // 5. 语言 / 平台 / 硬件信息（与 UA-CH 中声明的 Windows 保持一致）
    define(proto, 'languages', ['zh-CN', 'zh', 'en']);
    define(proto, 'language', 'zh-CN');
    define(proto, 'platform', 'Win32');
    define(proto, 'hardwareConcurrency', 8);
    define(proto, 'deviceMemory', 8);
    define(proto, 'maxTouchPoints', 0);
    define(proto, 'doNotTrack', null);
    define(proto, 'vendor', 'Google Inc.');
    define(proto, 'vendorSub', '');
    define(proto, 'productSub', '20030107');

    // 6. User-Agent Client Hints（Chrome 90+ 的强指纹来源，必须与 UA 完全一致）
    try {
      var brands = [
        { brand: 'Google Chrome', version: '131' },
        { brand: 'Chromium', version: '131' },
        { brand: 'Not_A Brand', version: '24' }
      ];
      var fullVersionList = [
        { brand: 'Google Chrome', version: '131.0.6778.33' },
        { brand: 'Chromium', version: '131.0.6778.33' },
        { brand: 'Not_A Brand', version: '24.0.0.0' }
      ];
      var uaData = {
        brands: brands,
        mobile: false,
        platform: 'Windows',
        getHighEntropyValues: mark(function () {
          return Promise.resolve({
            brands: brands,
            fullVersionList: fullVersionList,
            fullVersion: '131.0.6778.33',
            platform: 'Windows',
            platformVersion: '10.0.0',
            architecture: 'x86',
            bitness: '64',
            model: '',
            mobile: false,
            wow64: false,
            uaFullVersion: '131.0.6778.33'
          });
        }),
        toJSON: mark(function () {
          return { brands: brands, mobile: false, platform: 'Windows' };
        })
      };
      define(proto, 'userAgentData', uaData);
    } catch (e) {}

    // 7. WebGL 指纹：容器里是 SwiftShader 软件渲染，伪装成常见 Windows 集显
    try {
      var patchGL = function (GLProto) {
        if (!GLProto) return;
        var origGetParameter = GLProto.getParameter;
        GLProto.getParameter = mark(function getParameter(p) {
          if (p === 37445) return 'Google Inc. (Intel)';
          if (p === 37446) {
            return 'ANGLE (Intel, Intel(R) UHD Graphics 630 (0x00003E9B) Direct3D11 vs_5_0 ps_5_0, D3D11)';
          }
          return origGetParameter.call(this, p);
        });
      };
      patchGL(window.WebGLRenderingContext && WebGLRenderingContext.prototype);
      patchGL(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);
    } catch (e) {}

    // 8. Network Information / 媒体能力
    try {
      define(proto, 'connection', {
        downlink: 10, downlinkMax: Infinity, effectiveType: '4g', rtt: 50,
        saveData: false, type: 'ethernet', onchange: null
      });
    } catch (e) {}
    try {
      define(proto, 'webkitTemporaryStorage', undefined);
    } catch (e) {}

    // 9. 常见“无头检测”噪音：部分脚本通过 outerWidth/innerWidth 比例判断
    try {
      Object.defineProperty(window, 'outerWidth', { get: getter(Math.max(window.innerWidth, 1280)), configurable: true });
      Object.defineProperty(window, 'outerHeight', { get: getter(Math.max(window.innerHeight, 720)), configurable: true });
    } catch (e) {}
  } catch (e) {}
})();
