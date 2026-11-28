// Execute the pinned upstream module (not a reimplementation) with synthetic identity.
const fs = require('fs');
const vm = require('vm');
const crypto = require('crypto');
const path = require('path');
const root = path.resolve(__dirname, '../..');
const outputs = [];
for (const concept of [false, true]) {
  const appid = concept ? 3116 : 1005;
  const md5 = s => crypto.createHash('md5').update(s).digest('hex');
  const module = {exports: {}};
  const now = 1700000000123;
  const FakeDate = class extends Date { static now() {return now;} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'references/kgg081/upstream-song_url_new.js'), 'utf8'), {
    module, Date: FakeDate, require: () => ({randomString: () => {throw Error('Unexpected random device');}, appid, cryptoMd5: md5})
  });
  const cookie = {token: 'syntheticToken081', userid: 123456789, dfid: 'syntheticDfid081', KUGOU_API_MID: '12345678901234567890123456789012'};
  const request = module.exports({hash: '0123456789abcdef0123456789abcdef', cookie}, x => x);
  const body = JSON.stringify(request.data);
  const params = {dfid: cookie.dfid, mid: cookie.KUGOU_API_MID, uuid: '-', appid: String(appid), clientver: concept ? '11440' : '20489', clienttime: String(Math.floor(now / 1000)), token: cookie.token, userid: String(cookie.userid)};
  // Independent Node oracle for the upstream helper's sorted-params + exact-wire-body MD5.
  const salt = concept ? 'LnT6xpN3khm36zse0QzvmgTZ3waWdRSA' : 'OIlwieks28dk2k092lksi2UIkp';
  params.signature = md5(salt + Object.keys(params).sort().map(k => `${k}=${params[k]}`).join('') + body + salt);
  outputs.push({concept, body, params, router: request.headers['x-router'], path: request.url});
}
fs.writeFileSync(path.join(__dirname, 'reference-request.json'), JSON.stringify(outputs, null, 2) + '\n');
console.log('PASS: actual pinned song_url_new.js executed for standard/lite; synthetic independent Node request vectors written');
