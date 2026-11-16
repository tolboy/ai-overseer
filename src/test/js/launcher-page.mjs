// Optional browser-script check with Node 18+: node src/test/js/launcher-page.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../main/java/dev/oseer/dashboard/RunLauncherUi.java', import.meta.url), 'utf8');
const script = source.split('static final String SCRIPT = """')[1].split('"""')[0].replaceAll('\\\\', '\\');
const elements = new Map();
class Element {
    constructor(text = '', value = '') {
        this.textContent = text;
        this.value = value;
        this.children = [];
        this.dataset = {};
        this.events = {};
        this.className = '';
    }
    addEventListener(type, callback) { this.events[type] = callback; }
    fire(type, event = {target: this}) { return this.events[type]?.(event); }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    scrollIntoView() {}
}
const element = id => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
};
const document = new Element();
document.getElementById = element;
document.querySelector = selector => selector.startsWith('meta') ? {content:'token'} : {value:'do'};
document.querySelectorAll = () => [];
document.createElement = () => new Element();
const deferred = [];
const response = body => ({ok:true, json:async () => body});
const fetch = (url, options) => {
    if (url === '/api/config') return Promise.resolve(response({stages:[]}));
    if (url === '/api/models') return Promise.resolve(response({profiles:[]}));
    if (url === '/api/launch/jobs') return Promise.resolve(response({enabled:true, jobs:[{id:'a'}, {id:'b'}]}));
    return new Promise(resolve => deferred.push({url, payload:options?.body && JSON.parse(options.body), resolve:body => resolve(response(body))}));
};
const request = url => {
    const offset = deferred.findIndex(request => request.url === url);
    assert.ok(offset >= 0, `missing request for ${url}`);
    return deferred.splice(offset, 1)[0];
};
const flush = () => new Promise(setImmediate);
vm.runInNewContext(script, {document, window:{}, fetch, Option:Element, setTimeout:() => 0, clearTimeout:() => {}});

(async () => {
    await flush();
    const form = element('launch-form');
    const preview = element('launch-preview');
    const start = element('launch-start');
    const confirm = element('launch-confirm');
    const target = element('launch-project');
    target.value = 'project';
    const pendingInspect = preview.fire('click');
    request('/api/launch/inspect').resolve({project:'project', tasks:[]});
    await flush();
    const oldPreview = request('/api/launch/preview');
    form.fire('input', {target:element('launch-goal')});
    oldPreview.resolve({preview_id:'old', dry_run_exit:0});
    await pendingInspect;
    assert.equal(element('launch-plan').hidden, true);

    const failedPreview = preview.fire('click');
    request('/api/launch/preview').resolve({preview_id:'should-not-start', dry_run_exit:2, dry_run_code:'bad_config'});
    await failedPreview;
    assert.equal(confirm.disabled, true);
    assert.match(element('launch-plan-status').textContent, /bad_config/);
    confirm.checked = true;
    confirm.fire('change');
    assert.equal(start.disabled, true);

    const validPreview = preview.fire('click');
    request('/api/launch/preview').resolve({preview_id:'new', dry_run_exit:0, expires_at:new Date().toISOString()});
    await validPreview;
    confirm.checked = true;
    confirm.fire('change');
    assert.equal(start.disabled, false);
    form.fire('input', {target:element('launch-max-cost')});
    assert.equal(start.disabled, true);
    assert.equal(confirm.checked, false);

    const jobButton = index => element('launch-jobs').children[index].children[4].children[0];
    jobButton(0).fire('click');
    request('/api/launch/jobs/a').resolve({id:'a', label:'First', run_id:'run-a', project:'project', state:'finished', narration:[]});
    await flush();
    const timelineA = request('/api/runs/run-a?job=a');
    jobButton(1).fire('click');
    request('/api/launch/jobs/b').resolve({id:'b', label:'Second', run_id:'run-b', project:'project', state:'stopped', narration:[],
        decision:{updated_at:'timestamp', expires_at:'2000-01-01T00:00:00Z', options:['retry', 'waive']}});
    await flush();
    request('/api/runs/run-b?job=b').resolve({state:'finished', contract_gap_ids:['case / 1']});
    await flush();
    timelineA.resolve({state:'finished', ok:true});
    await flush();
    assert.equal(element('launch-job-title').textContent, 'Second · b');
    assert.equal(element('launch-land').hidden, true);
    assert.equal(element('launch-decision').hidden, false);
    assert.equal(element('launch-open-run').disabled, false);
    const choice = element('launch-choice');
    assert.equal(element('launch-finding-field').hidden, true);
    choice.value = 'waive';
    choice.fire('change');
    assert.equal(element('launch-finding-field').hidden, false);
    assert.equal(element('launch-expired-field').hidden, false);
    assert.equal(element('launch-answer').disabled, true);
    const finding = element('launch-finding');
    finding.value = element('launch-finding-ids').children[0].value;
    finding.fire('input');
    assert.equal(element('launch-answer').disabled, true);
    const expiry = element('launch-expired-confirm');
    expiry.checked = true;
    expiry.fire('change');
    assert.equal(element('launch-answer').disabled, false);
    element('launch-note').value = 'accept the contract gap';
    element('launch-answer-confirm').checked = true;
    element('launch-answer').fire('click');
    const answer = request('/api/launch/answer');
    assert.equal(answer.payload.expected_updated_at, 'timestamp');
    assert.equal(answer.payload.run_id, 'run-b');
    assert.equal(answer.payload.finding, 'case / 1');
    assert.equal(answer.payload.acknowledge_expired, true);
    element('launch-land-confirm').checked = true;
    jobButton(0).fire('click');
    assert.equal(element('launch-note').value, '');
    assert.equal(finding.value, '');
    assert.equal(expiry.checked, false);
    assert.equal(element('launch-answer-confirm').checked, false);
    assert.equal(element('launch-land-confirm').checked, false);
    answer.resolve({id:'continued'});
    await flush();
    assert.equal(element('launch-job-title').textContent, 'a');
    console.log('Launcher preview, job race, decision and confirmation checks passed');
})().catch(error => {console.error(error); process.exitCode = 1;});
