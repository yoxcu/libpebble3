// XMLHttpRequest shim for GraalJS (uses a custom _xhrDecodeBase64 since GraalJS lacks Uint8Array.fromBase64)

function XMLHttpRequest() {
    this.readyState = XMLHttpRequest.UNSENT;
    this.responseType = "";
    this.status = 0;
    this.statusText = "";
    this.response = null;
    this.responseText = null;
    this.responseHeaders = {};
    this.onload = null;
    this.onloadend = null;
    this.onloadstart = null;
    this.onerror = null;
    this.onabort = null;
    this.onprogress = null;
    this.onreadystatechange = null;
    this.ontimeout = null;
    this._listeners = new Map();
    this._instanceID = _XMLHTTPRequestManager.getXHRInstanceID();
    XMLHttpRequest._instances.set(this._instanceID, this);
}

XMLHttpRequest._instances = new Map();
XMLHttpRequest.UNSENT = 0;
XMLHttpRequest.OPENED = 1;
XMLHttpRequest.HEADERS_RECEIVED = 2;
XMLHttpRequest.LOADING = 3;
XMLHttpRequest.DONE = 4;

XMLHttpRequest.prototype.addEventListener = function(type, listener) {
    if (!this._listeners.has(type)) {
        this._listeners.set(type, []);
    }
    this._listeners.get(type).push(listener);
};

XMLHttpRequest.prototype.removeEventListener = function(type, listener) {
    if (this._listeners.has(type)) {
        const listeners = this._listeners.get(type);
        const index = listeners.indexOf(listener);
        if (index !== -1) {
            listeners.splice(index, 1);
        }
    }
};

XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
    if (async === undefined) async = true;
    if (!user) user = "";
    if (!password) password = "";
    if (!method) throw new Error("SyntaxError: Method is required.");
    if (!url) throw new Error("SyntaxError: URL is required.");
    _XMLHTTPRequestManager.open(this._instanceID, method, url, async, user, password);
    this.readyState = XMLHttpRequest.OPENED;
};

XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
    _XMLHTTPRequestManager.setRequestHeader(this._instanceID, header, value);
};

XMLHttpRequest.prototype.getAllResponseHeaders = function() {
    if (this.readyState < XMLHttpRequest.HEADERS_RECEIVED) {
        throw new Error("InvalidStateError: The object is in an invalid state (not sent).");
    }
    const parts = [];
    const h = this.responseHeaders;
    for (var key in h) {
        if (Object.prototype.hasOwnProperty.call(h, key)) {
            parts.push(key + ": " + h[key]);
        }
    }
    return parts.join("\r\n");
};

XMLHttpRequest.prototype.getResponseHeader = function(header) {
    if (this.readyState < XMLHttpRequest.HEADERS_RECEIVED) {
        throw new Error("InvalidStateError: The object is in an invalid state (not sent).");
    }
    return this.responseHeaders[header.toLowerCase()] || null;
};

XMLHttpRequest.prototype.send = function(data) {
    if (this.readyState !== XMLHttpRequest.OPENED) {
        throw new Error("InvalidStateError: The object is in an invalid state (not opened).");
    }
    if (this.responseType === "blob" || this.responseType === "document") {
        throw new Error("Blob and document response types are not supported.");
    }
    _XMLHTTPRequestManager.send(this._instanceID, this.responseType, data);
};

XMLHttpRequest.prototype.abort = function() {
    _XMLHTTPRequestManager.abort(this._instanceID);
};

XMLHttpRequest.prototype._onResponseComplete = function(responseHeaders, status, statusText, body) {
    this.responseHeaders = responseHeaders;
    this.status = status;
    this.statusText = statusText;
    switch (this.responseType) {
        case "arraybuffer":
            this.responseText = null;
            try {
                this.response = new Uint8Array(_xhrDecodeBase64(body));
            } catch (e) {
                this.response = null;
            }
            break;
        case "blob":
        case "document":
            this.response = null;
            break;
        case "json":
            this.responseText = body;
            try {
                this.response = JSON.parse(body);
            } catch (e) {
                console.error("JSON parse error:", e);
                this._dispatchEvent("error", { type: "error", target: this, message: "JSON parse error: " + e.message });
                this.response = null;
            }
            break;
        default:
            this.response = body;
            this.responseText = body;
            break;
    }
};

XMLHttpRequest.prototype._dispatchEvent = function(type, event) {
    if (this._listeners.has(type)) {
        const listeners = this._listeners.get(type);
        for (var i = 0; i < listeners.length; i++) {
            listeners[i](event);
        }
    }
    switch (type) {
        case "load":            this.onload            && this.onload(event);            break;
        case "loadend":         this.onloadend         && this.onloadend(event);         break;
        case "loadstart":       this.onloadstart       && this.onloadstart(event);       break;
        case "error":           this.onerror           && this.onerror(event);           break;
        case "abort":           this.onabort           && this.onabort(event);           break;
        case "progress":        this.onprogress        && this.onprogress(event);        break;
        case "readystatechange":this.onreadystatechange && this.onreadystatechange(event);break;
        case "timeout":         this.ontimeout         && this.ontimeout(event);         break;
        default: console.warn("XHR - Unknown event type:", type); break;
    }
};

function _xhrDecodeBase64(base64) {
    const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    const bytes = [];
    const s = base64.replace(/[^A-Za-z0-9+/]/g, "");
    for (var i = 0; i < s.length; i += 4) {
        const a = chars.indexOf(s[i]),   b = chars.indexOf(s[i+1]);
        const c = chars.indexOf(s[i+2]), d = chars.indexOf(s[i+3]);
        bytes.push((a << 2) | (b >> 4));
        if (c !== -1) bytes.push(((b & 0xf) << 4) | (c >> 2));
        if (d !== -1) bytes.push(((c & 0x3) << 6) | d);
    }
    return bytes;
}

globalThis.XMLHttpRequest = XMLHttpRequest;
