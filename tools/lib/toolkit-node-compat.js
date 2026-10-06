// Compatibility shims for the EWM Git Server Toolkit 7.2 scripts on current Node.js.
// Preload with: NODE_OPTIONS="--require <this file>"
const util = require("util");

// util.log was removed in Node.js 23; the toolkit's tracing still calls it.
if (typeof util.log !== "function") {
	util.log = (...args) => console.log(new Date().toISOString() + " - " + util.format(...args));
}

// Against EWM 7.2 with form login, the toolkit's anonymous pre-login gets no cookie and the login request
// then carries "cookie: undefined", which Node.js rejects (ERR_HTTP_INVALID_HEADER_VALUE). Drop such headers.
for (const name of ["http", "https"]) {
	const module = require(name);
	for (const method of ["request", "get"]) {
		const original = module[method];
		module[method] = function (...args) {
			for (const arg of args) {
				if (arg && typeof arg === "object" && !(arg instanceof URL) && arg.headers) {
					for (const key of Object.keys(arg.headers)) {
						if (arg.headers[key] === undefined) {
							delete arg.headers[key];
						}
					}
				}
			}
			return original.apply(this, args);
		};
	}
}
