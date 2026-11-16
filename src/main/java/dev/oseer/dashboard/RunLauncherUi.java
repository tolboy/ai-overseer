package dev.oseer.dashboard;

/**
 * The Launch tab: the form that becomes {@code oseer do} or {@code oseer run}, the plan the
 * operator approves before paid calls, and the live view of the job it started.
 *
 * <p>Every string is a message key; the page talks only to {@code /api/launch/*},
 * {@code /api/config} (stage candidates) and {@code /api/runs/<run>?job=} (the timeline).
 */
final class RunLauncherUi {
    private RunLauncherUi() {}

    static final String CSS = """
            #launch fieldset{border:1px solid #405363;border-radius:8px;margin:12px 0;padding:12px;min-width:0}
            #launch label{display:flex;flex-direction:column;gap:5px;min-width:0}#launch label.check{flex-direction:row;align-items:center;gap:8px}
            #launch input,#launch select,#launch textarea{min-width:0;box-sizing:border-box}#launch textarea{width:100%;min-height:90px}
            .launch-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin:10px 0}
            .launch-kinds{display:flex;gap:18px;flex-wrap:wrap;margin-bottom:10px}
            .launch-stage{display:grid;grid-template-columns:minmax(110px,1fr) 3fr minmax(90px,1fr);gap:10px;align-items:end;margin:6px 0}
            #launch pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#0f161c;border:1px solid #30404d;border-radius:8px;padding:10px;max-height:420px;overflow:auto}
            #launch button{margin:6px 6px 6px 0}#launch button:disabled{opacity:.5;cursor:default}
            #launch-target.ok{color:#9ee9c6}#launch-job .now{display:flex;flex-wrap:wrap;gap:12px;margin:8px 0}
            #launch-job .now span{background:#19232c;border:1px solid #30404d;border-radius:8px;padding:8px 12px}#launch-job .now b{display:block;font-size:17px}
            #launch-steps li{margin:4px 0}#launch-steps .code{color:#ffb4a8}#launch-steps .switch{color:#ffd479}
            #launch-waiting{position:fixed;right:20px;bottom:20px;z-index:20;background:#ffd479;color:#1b1400;border:0;border-radius:20px;padding:10px 16px;font-weight:600;box-shadow:0 4px 14px #0008}
            #launch-decision{border-color:#ffd479}
            @media(max-width:700px){.launch-stage{grid-template-columns:1fr}}
            """;

    static final String HTML = """
            <section id="launch" aria-labelledby="launch-title">
            <h2 id="launch-title">{{t:launch.title}}</h2>
            <p class="muted">{{t:launch.help}}</p>
            <p id="launch-disabled" class="bad" hidden>{{t:launch.disabled}}</p>
            <form id="launch-form" novalidate>
            <fieldset><legend>{{t:launch.target}}</legend>
            <label>{{t:launch.project}}<input id="launch-project" autocomplete="off" spellcheck="false" placeholder="/path/to/repository"></label>
            <button type="button" id="launch-inspect">{{t:launch.inspect}}</button>
            <p id="launch-target" role="status" aria-live="polite"></p>
            </fieldset>
            <fieldset><legend>{{t:launch.what}}</legend>
            <div class="launch-kinds">
            <label class="check"><input type="radio" name="launch-kind" value="do" checked> {{t:launch.kind_do}}</label>
            <label class="check"><input type="radio" name="launch-kind" value="run"> {{t:launch.kind_run}}</label>
            </div>
            <div id="launch-do">
            <label>{{t:launch.goal}}<textarea id="launch-goal" maxlength="8000"></textarea></label>
            <div class="launch-grid">
            <label>{{t:launch.task_id}}<input id="launch-task-id" autocomplete="off" placeholder="{{t:launch.auto}}"></label>
            <label>{{t:launch.scope}}<input id="launch-scope" autocomplete="off" placeholder="{{t:launch.from_project}}"></label>
            <label>{{t:launch.risk}}<select id="launch-risk"><option value="">{{t:launch.from_project}}</option><option value="low">low</option><option value="medium">medium</option><option value="high">high</option></select></label>
            <label>{{t:launch.mode}}<select id="launch-mode"><option value="">{{t:launch.no_mode}}</option><option value="quick">quick</option><option value="bug">bug</option><option value="task">task</option><option value="refactor">refactor</option></select></label>
            <label>{{t:launch.prepare}}<select id="launch-prepare"><option value="auto">auto</option><option value="off">off</option><option value="always">always</option></select></label>
            <label>{{t:launch.max_calls}}<input id="launch-max-calls" type="number" min="1" max="50" step="1" value="4"></label>
            <label>{{t:launch.max_cost}}<input id="launch-max-cost" type="number" min="0" max="10000" step="0.05" value="0.5"></label>
            </div>
            <label class="check"><input type="checkbox" id="launch-draft-only"> {{t:launch.draft_only}}</label>
            <label class="check"><input type="checkbox" id="launch-in-place"> {{t:launch.in_place}}</label>
            </div>
            <div id="launch-run" hidden>
            <div class="launch-grid">
            <label>{{t:launch.task}}<select id="launch-task"><option value="">{{t:launch.inspect_first}}</option></select></label>
            <label>{{t:launch.continue_from}}<input id="launch-continue" autocomplete="off" placeholder="run-…"></label>
            <label>{{t:launch.prepare}}<select id="launch-run-prepare"><option value="">{{t:launch.default}}</option><option value="off">off</option><option value="auto">auto</option><option value="always">always</option></select></label>
            </div>
            <p class="muted">{{t:launch.budgets_in_contract}}</p>
            </div>
            </fieldset>
            <fieldset><legend>{{t:launch.vendors}}</legend>
            <p class="muted">{{t:launch.vendors_help}}</p>
            <div id="launch-stages"></div>
            <label class="check"><input type="checkbox" id="launch-no-orca"> {{t:launch.no_orca_gate}}</label>
            </fieldset>
            <button type="button" id="launch-preview">{{t:launch.preview}}</button>
            <p id="launch-status" role="status" aria-live="polite"></p>
            </form>
            <section id="launch-plan" hidden aria-labelledby="launch-plan-title">
            <h3 id="launch-plan-title">{{t:launch.plan_title}}</h3>
            <p class="muted">{{t:launch.plan_help}}</p>
            <p id="launch-plan-target"></p>
            <pre id="launch-plan-command"></pre>
            <pre id="launch-plan-text"></pre>
            <label class="check"><input type="checkbox" id="launch-confirm"> {{t:launch.confirm}}</label>
            <button type="button" id="launch-start" disabled>{{t:launch.start}}</button>
            <button type="button" id="launch-discard">{{t:launch.discard}}</button>
            <p id="launch-plan-status" role="status" aria-live="polite"></p>
            </section>
            <h3>{{t:launch.jobs}}</h3>
            <div class="scroll" tabindex="0"><table><thead><tr><th>{{t:launch.job}}</th><th>{{t:launch.state}}</th><th>{{t:launch.run}}</th><th>{{t:launch.elapsed}}</th><th></th></tr></thead><tbody id="launch-jobs"></tbody></table></div>
            <p id="launch-jobs-empty" class="muted" hidden>{{t:launch.no_jobs}}</p>
            <div class="launch-grid"><label>{{t:launch.attach_run}}<input id="launch-attach-run" autocomplete="off" placeholder="run-…"></label></div>
            <button type="button" id="launch-attach">{{t:launch.attach}}</button> <span id="launch-attach-status" role="status"></span>
            <section id="launch-job" hidden aria-labelledby="launch-job-title">
            <h3 id="launch-job-title"></h3>
            <fieldset id="launch-decision" hidden><legend>{{t:launch.decision}}</legend>
            <p id="launch-decision-reason"></p>
            <div class="launch-grid">
            <label>{{t:launch.choice}}<select id="launch-choice"></select></label>
            <label>{{t:launch.switch_stage}}<select id="launch-switch-stage"><option value="">{{t:launch.no_switch}}</option></select></label>
            <label>{{t:launch.switch_profile}}<select id="launch-switch-profile"><option value="">—</option></select></label>
            </div>
            <label>{{t:launch.note}}<input id="launch-note" maxlength="2000" autocomplete="off"></label>
            <label class="check"><input type="checkbox" id="launch-answer-confirm"> {{t:launch.confirm_continue}}</label>
            <button type="button" id="launch-answer">{{t:launch.answer}}</button>
            <p id="launch-answer-status" role="status" aria-live="polite"></p>
            </fieldset>
            <div class="now" id="launch-now" aria-live="polite"></div>
            <button type="button" id="launch-cancel">{{t:launch.cancel}}</button>
            <button type="button" id="launch-open-run">{{t:launch.open_timeline}}</button>
            <p id="launch-job-status" role="status" aria-live="polite"></p>
            <fieldset id="launch-land" hidden><legend>{{t:launch.land}}</legend>
            <p class="muted">{{t:launch.land_help}}</p>
            <label class="check"><input type="checkbox" id="launch-land-commit"> {{t:launch.land_commit}}</label>
            <label class="check"><input type="checkbox" id="launch-land-push"> {{t:launch.land_push}}</label>
            <label class="check"><input type="checkbox" id="launch-land-pr"> {{t:launch.land_pr}}</label>
            <label class="check"><input type="checkbox" id="launch-land-confirm"> {{t:launch.land_confirm}}</label>
            <button type="button" id="launch-land-go">{{t:launch.land_go}}</button>
            <p id="launch-land-status" role="status" aria-live="polite"></p>
            </fieldset>
            <h4>{{t:launch.steps}}</h4><ol id="launch-steps"></ol>
            <h4>{{t:launch.narration}}</h4><pre id="launch-tail" tabindex="0"></pre>
            </section>
            <button type="button" id="launch-waiting" hidden>{{t:launch.waiting_for_you}} →</button>
            </section>
            """;

    static final String SCRIPT = """
            (()=>{const g=id=>document.getElementById(id);if(!g('launch'))return;
            const key=document.querySelector('meta[name=oseer-settings-token]').content;
            const mk=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n};
            const stateText={running:'{{t:launch.state_running}}',finished:'{{t:launch.state_finished}}',failed:'{{t:launch.state_failed}}',stopped:'{{t:launch.state_stopped}}',cancelled:'{{t:launch.state_cancelled}}'};
            const codes={project_not_git:'{{t:launch.err_not_git}}',project_not_repo_root:'{{t:launch.err_not_root}}',project_inside_oseer:'{{t:launch.err_inside_oseer}}',project_missing:'{{t:launch.err_missing}}',project_not_absolute:'{{t:launch.err_not_absolute}}',
            project_busy:'{{t:launch.err_busy}}',preview_stale:'{{t:launch.err_stale}}',preview_unknown:'{{t:launch.err_stale}}',confirmation_required:'{{t:launch.err_confirm}}',goal_invalid:'{{t:launch.err_goal}}',
            task_unknown:'{{t:launch.err_task}}',launcher_disabled:'{{t:launch.disabled}}',preview_timeout:'{{t:launch.err_timeout}}',decision_not_pending:'{{t:launch.err_not_pending}}',run_unknown:'{{t:launch.err_run_unknown}}'};
            const dur=ms=>{if(ms==null)return '—';const s=Math.round(ms/1000);return s<60?s+' {{t:launch.unit_s}}':Math.floor(s/60)+' {{t:launch.unit_min}} '+(s%60)+' {{t:launch.unit_s}}'};
            async function api(action,payload){const r=await fetch('/api/launch/'+action,{method:'POST',headers:{'Content-Type':'application/json','X-Overseer-Token':key},body:JSON.stringify(payload)});
            let body={};try{body=await r.json()}catch(e){}if(!r.ok){const plain=['project_not_repo_root','project_inside_oseer'].includes(body.code);const e=Error(codes[body.code]?codes[body.code]+(body.message?(plain?' '+body.message:' ('+body.message+')'):''):(body.message||body.code||('HTTP '+r.status)));e.code=body.code;throw e}return body}
            let target=null,stages=[],plan=null,job=null,jobTimer=null,jobView=null;
            const kind=()=>document.querySelector('input[name=launch-kind]:checked').value;
            for(const r of document.querySelectorAll('input[name=launch-kind]'))r.addEventListener('change',()=>{g('launch-do').hidden=kind()!=='do';g('launch-run').hidden=kind()!=='run';discard()});
            async function inspect(){const s=g('launch-target');s.className='';s.textContent='{{t:launch.checking}}';
            try{const t=await api('inspect',{project:g('launch-project').value});target=t;g('launch-project').value=t.project;
            s.className='ok';s.textContent=t.project+' · {{t:launch.branch}} '+(t.branch??'—')+' · HEAD '+(t.head??'—')+' · '+(t.dirty_files?t.dirty_files+' {{t:launch.dirty}}':'{{t:launch.clean}}')+' · '+((t.tasks||[]).length?'{{t:launch.task_list}} '+t.tasks.join(', '):'{{t:launch.no_tasks}}')+(t.project_config?'':' · {{t:launch.no_project_yaml}}')+(t.busy?' · {{t:launch.err_busy}}':'');
            const sel=g('launch-task');sel.replaceChildren();for(const name of t.tasks||[])sel.append(new Option(name,name));if(!(t.tasks||[]).length)sel.append(new Option('{{t:launch.no_tasks}}',''))}
            catch(e){target=null;s.className='bad';s.textContent=e.message}}
            g('launch-inspect').addEventListener('click',inspect);g('launch-project').addEventListener('change',()=>{target=null;discard()});
            let profiles=[];
            async function loadStages(){try{const r=await fetch('/api/config',{cache:'no-store'});const c=await r.json();stages=(c.stages||[]).filter(s=>s.kind==='role'&&s.stage)}catch(e){stages=[]}
            try{const r=await fetch('/api/models',{cache:'no-store'});const m=await r.json();profiles=m.profiles||[]}catch(e){profiles=[]}drawStages()}
            function profileSelect(s,blank){const sel=mk('select');sel.append(new Option(blank,''));const seen=new Set();
            const add=(name,vendor,model,note)=>{if(seen.has(name))return;seen.add(name);sel.append(new Option(name+' · '+(vendor??'—')+(model?' · '+model:'')+(note?' — '+note:''),name))};
            for(const c of s.candidates||[])add(c.profile,c.vendor,c.model,c.eligible===false?'{{t:launch.not_eligible}}'+(c.rejected?' '+c.rejected:''):'');
            for(const p of profiles)if(p.role===s.role)add(p.name,p.vendor,p.model,'{{t:launch.not_in_roster}}');return sel}
            function drawStages(){const box=g('launch-stages');box.replaceChildren();for(const s of stages){const row=mk('div',null,'launch-stage');row.dataset.stage=s.stage;
            const name=mk('label',s.stage+(s.role?' · '+s.role:''));row.append(name);
            const pl=mk('label','{{t:launch.vendor_for_stage}}');const ps=profileSelect(s,'{{t:launch.policy_choice}} ('+(s.would_run??'—')+')');ps.dataset.use=s.stage;pl.append(ps);row.append(pl);
            const el2=mk('label','{{t:launch.effort}}');const es=mk('select');for(const v of ['','low','medium','high','xhigh','max'])es.append(new Option(v||'{{t:launch.default}}',v));es.dataset.effort=s.stage;el2.append(es);row.append(el2);box.append(row)}
            const ss=g('launch-switch-stage');ss.replaceChildren(new Option('{{t:launch.no_switch}}',''));for(const s of stages)ss.append(new Option(s.stage,s.stage))}
            g('launch-switch-stage').addEventListener('change',()=>{const s=stages.find(x=>x.stage===g('launch-switch-stage').value);const sel=g('launch-switch-profile');sel.replaceChildren(new Option('—',''));if(s)for(const o of profileSelect(s,'—').options)if(o.value)sel.append(new Option(o.text,o.value))});
            function params(){const p={project:g('launch-project').value,no_orca_gate:g('launch-no-orca').checked,use:{},effort:{}};
            for(const s of document.querySelectorAll('[data-use]'))if(s.value)p.use[s.dataset.use]=s.value;for(const s of document.querySelectorAll('[data-effort]'))if(s.value)p.effort[s.dataset.effort]=s.value;
            if(kind()==='do'){Object.assign(p,{goal:g('launch-goal').value,task_id:g('launch-task-id').value,scope:g('launch-scope').value,risk:g('launch-risk').value,mode:g('launch-mode').value,prepare:g('launch-prepare').value,
            max_role_runs:g('launch-max-calls').value,max_cost_usd:g('launch-max-cost').value,draft_only:g('launch-draft-only').checked,in_place:g('launch-in-place').checked})}
            else Object.assign(p,{task:g('launch-task').value,continue:g('launch-continue').value,prepare:g('launch-run-prepare').value});return p}
            function discard(){plan=null;g('launch-plan').hidden=true;g('launch-confirm').checked=false;g('launch-start').disabled=true}
            g('launch-discard').addEventListener('click',discard);
            g('launch-preview').addEventListener('click',async()=>{const s=g('launch-status');discard();if(!target)await inspect();if(!target)return;
            s.className='';s.textContent='{{t:launch.previewing}}';g('launch-preview').disabled=true;
            try{plan=await api('preview',{kind:kind(),params:params()});s.textContent='';g('launch-plan').hidden=false;
            g('launch-plan-target').textContent='{{t:launch.target}}: '+plan.project+' · '+'{{t:launch.run}} '+plan.run_id+(plan.busy?' · {{t:launch.err_busy}}':'');
            g('launch-plan-command').textContent=(plan.command||[]).join('\\n');g('launch-plan-text').textContent=(plan.plan||[]).join('\\n');
            g('launch-plan-status').textContent='{{t:launch.expires}} '+new Date(plan.expires_at).toLocaleTimeString();g('launch-plan').scrollIntoView({block:'start'})}
            catch(e){s.className='bad';s.textContent=e.message}finally{g('launch-preview').disabled=false}});
            g('launch-confirm').addEventListener('change',()=>{g('launch-start').disabled=!(plan&&g('launch-confirm').checked)});
            g('launch-start').addEventListener('click',async()=>{askToNotify();const s=g('launch-plan-status');if(!plan)return;g('launch-start').disabled=true;
            try{const started=await api('start',{preview_id:plan.preview_id,confirm:g('launch-confirm').checked});discard();openJob(started.id)}
            catch(e){s.className='bad';s.textContent=e.message;if(e.code==='preview_stale'||e.code==='preview_unknown')plan=null}});
            async function jobs(){try{const r=await fetch('/api/launch/jobs',{cache:'no-store'});const d=await r.json();g('launch-disabled').hidden=d.enabled!==false;g('launch-form').hidden=d.enabled===false;
            const body=g('launch-jobs');body.replaceChildren();for(const j of d.jobs||[]){const tr=mk('tr');for(const v of [j.label,stateText[j.state]??j.state,j.run_id??'—',dur(j.elapsed_ms)])tr.append(mk('td',v));
            const td=mk('td');const b=mk('button','{{t:launch.open}}');b.type='button';b.addEventListener('click',()=>openJob(j.id));td.append(b);tr.append(td);body.append(tr)}
            g('launch-jobs-empty').hidden=(d.jobs||[]).length>0||!!job;announce(d.jobs||[])}catch(e){}finally{setTimeout(jobs,3000)}}
            const baseTitle=document.title,announced=new Set();let primed=false;
            function announce(list){const waiting=list.filter(j=>j.waiting);document.title=(waiting.length?'('+waiting.length+') {{t:launch.waiting_for_you}} · ':'')+baseTitle;
            for(const j of waiting){if(announced.has(j.id))continue;announced.add(j.id);if(primed&&'Notification'in window&&Notification.permission==='granted'){const n=new Notification('{{t:launch.waiting_for_you}}',{body:j.label+(j.run_id?' · '+j.run_id:''),tag:j.id});n.onclick=()=>{focus();openJob(j.id)}}}
            for(const id of [...announced])if(!waiting.some(j=>j.id===id))announced.delete(id);primed=true}
            function askToNotify(){if('Notification'in window&&Notification.permission==='default')Notification.requestPermission().catch(()=>{})}
            function openJob(id){job=id;clearTimeout(jobTimer);g('launch-job').hidden=false;g('launch-steps').replaceChildren();g('launch-tail').textContent='';pollJob();g('launch-job').scrollIntoView({block:'start'})}
            async function pollJob(){const id=job;if(!id)return;let v;try{const r=await fetch('/api/launch/jobs/'+encodeURIComponent(id),{cache:'no-store'});if(!r.ok)throw Error();v=await r.json()}catch(e){g('launch-job-status').textContent='{{t:launch.unreachable}}';jobTimer=setTimeout(pollJob,4000);return}
            if(id!==job)return;jobView=v;let timeline=null;if(v.run_id&&(v.narration||[]).length){try{const r=await fetch('/api/runs/'+encodeURIComponent(v.run_id)+'?job='+encodeURIComponent(id),{cache:'no-store'});if(r.ok)timeline=await r.json()}catch(e){}}
            drawJob(v,timeline);if(v.state==='running'||(timeline&&timeline.state==='running'))jobTimer=setTimeout(pollJob,2000)}
            function drawJob(v,t){g('launch-job-title').textContent=v.label+' · '+v.id;const now=g('launch-now');now.replaceChildren();
            const steps=(t&&t.steps)||[];const current=steps.find(s=>s.status==='running')??steps[steps.length-1];
            const fact=(k,val)=>{const s=mk('span',k);s.append(mk('b',val));now.append(s)};
            fact('{{t:launch.state}}',stateText[v.state]??v.state);fact('{{t:launch.stage_now}}',current?(current.stage??current.step??'—')+(current.status?' · '+current.status:''):v.state==='running'?'{{t:launch.starting}}':'—');
            fact('{{t:launch.vendor_now}}',current?[current.profile,current.vendor].filter(Boolean).join(' · ')||'—':'—');
            fact('{{t:launch.elapsed}}',dur(v.elapsed_ms));
            if(t&&t.totals)fact('{{t:launch.cost}}',costText(t.totals.cost_usd_known,t.totals.unpriced_calls??t.totals.unpriced_steps));
            g('launch-cancel').disabled=v.state!=='running';g('launch-open-run').disabled=!v.run_id||!t;
            const res=v.state==='running'?{}:(v.result||{});g('launch-job-status').textContent=[v.note,res.code,res.reason,res.next_action,res.message,res.next].filter(Boolean).join(' · ');
            const list=g('launch-steps');list.replaceChildren();for(const s of steps){const li=mk('li',[s.stage??s.step,s.status,[s.profile,s.vendor].filter(Boolean).join('/'),s.duration_ms!=null?dur(s.duration_ms):null,s.code,s.http_status?'HTTP '+s.http_status:null].filter(Boolean).join(' · '));
            for(const f of s.failed_attempts||[])li.append(mk('div','{{t:timeline.attempt_failed}} '+[f.profile,f.vendor,f.http_status?'HTTP '+f.http_status:null,f.code].filter(Boolean).join(' · '),'code'));
            for(const f of s.failovers||[])li.append(mk('div','{{t:timeline.switched}} '+[(f.from_profile??'—')+' → '+(f.to_profile??'—'),f.cause].filter(Boolean).join(' · '),'switch'));list.append(li)}
            const tail=g('launch-tail');const atEnd=tail.scrollTop+tail.clientHeight>=tail.scrollHeight-8;tail.textContent=(v.narration||[]).join('\\n');if(atEnd)tail.scrollTop=tail.scrollHeight;
            const d=v.decision;g('launch-decision').hidden=!d;if(d){g('launch-decision-reason').textContent=(d.kind??'')+' · '+(d.reason??'');const c=g('launch-choice');const keep=c.value;c.replaceChildren();for(const o of d.options||[])c.append(new Option(o,o));if((d.options||[]).includes(keep))c.value=keep}waitingLink();
            g('launch-land').hidden=!(v.run_id&&v.state!=='running'&&!d&&t&&t.ok===true)}
            function waitingLink(){const f=g('launch-decision'),b=g('launch-waiting');if(f.hidden||g('launch-job').hidden){b.hidden=true;return}const box=f.getBoundingClientRect();b.hidden=box.top<innerHeight&&box.bottom>0}
            addEventListener('scroll',waitingLink,{passive:true});g('launch-waiting').addEventListener('click',()=>{g('launch-decision').scrollIntoView({block:'start',behavior:'smooth'});g('launch-choice').focus({preventScroll:true})});
            g('launch-attach').addEventListener('click',async()=>{const s=g('launch-attach-status');s.className='';try{const j=await api('attach',{project:g('launch-project').value,run_id:g('launch-attach-run').value.trim()});s.textContent='';openJob(j.id)}catch(e){s.className='bad';s.textContent=e.message}});
                        g('launch-cancel').addEventListener('click',async()=>{if(!job)return;try{await api('cancel',{job});pollJob()}catch(e){g('launch-job-status').textContent=e.message}});
            g('launch-open-run').addEventListener('click',()=>{if(jobView&&jobView.run_id)window.overseerOpenRun(jobView.run_id,job)});
            g('launch-answer').addEventListener('click',async()=>{askToNotify();const s=g('launch-answer-status');if(!jobView)return;const p={use:{}};const st=g('launch-switch-stage').value,pr=g('launch-switch-profile').value;if(st&&pr)p.use[st]=pr;
            try{const started=await api('answer',{project:jobView.project,run_id:jobView.run_id,decision:g('launch-choice').value,note:g('launch-note').value,confirm:g('launch-answer-confirm').checked,params:p});s.textContent='';g('launch-answer-confirm').checked=false;openJob(started.id)}
            catch(e){s.className='bad';s.textContent=e.message}});
            g('launch-land-go').addEventListener('click',async()=>{const s=g('launch-land-status');if(!jobView)return;
            try{const started=await api('land',{project:jobView.project,run_id:jobView.run_id,commit:g('launch-land-commit').checked,push:g('launch-land-push').checked,pull_request:g('launch-land-pr').checked,confirm_remote:g('launch-land-confirm').checked});s.textContent='';openJob(started.id)}
            catch(e){s.className='bad';s.textContent=e.message}});
            loadStages();jobs()})();
            """;
}
