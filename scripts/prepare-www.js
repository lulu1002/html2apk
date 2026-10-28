// Copia o front-end (public/) para www/, a pasta que o Capacitor empacota no APK.
const fs = require("fs");
fs.rmSync("www", { recursive: true, force: true });
fs.cpSync("public", "www", { recursive: true });
console.log("www/ pronto");
