package dev.oseer.dashboard;

/**
 * The dashboard's frame: a header, one tab per job (overview, runs, next run, roles,
 * analytics, connections), the overview panel and the run timeline.
 *
 * Plain HTML, CSS and script inlined into the one page the dashboard serves: nothing is
 * fetched from another origin, so it works offline and under the page's strict CSP. Without
 * script every panel stays visible, in order, as one long page. All data is written with
 * {@code textContent}; no response text is ever parsed as markup.
 */
final class DashboardShell {
    private DashboardShell() {}

    static final String CSS = """
            :root{--bg:#0f151b;--panel:#151e26;--line:#2a3945;--muted:#97a9b8;--ink:#e6eef5;--ok:#7fd8ae;--bad:#ff9d87;--warn:#f0cf8d;--idle:#7e8f9d;--accent:#9ee9c6}
            body{max-width:1440px;margin:0 auto;padding:0 28px 48px;background:var(--bg)}
            .banner-warning{background:#3d2f17;border:1px solid #a57b2c;border-radius:8px;padding:10px 14px;margin:10px 0}.banner-warning p{margin:4px 0 0}
            .topbar-side{display:flex;gap:14px;align-items:center;flex-wrap:wrap}.lang{font-size:12px;color:var(--muted);display:flex;gap:6px;align-items:center}.lang select{width:auto;padding:2px 6px}
            .topbar{position:sticky;top:0;z-index:5;background:var(--bg);border-bottom:1px solid var(--line);padding:18px 0 0;margin-bottom:8px}
            .brand{display:flex;align-items:baseline;gap:14px;flex-wrap:wrap}.brand h1{font-size:22px;margin:0;letter-spacing:.2px}
            .brand h1 span{color:var(--muted);font-weight:400}.brand #path{margin:0;font-size:12px}.brand #connection{margin-left:auto;font-size:12px}
            .tabs{display:flex;gap:4px;overflow-x:auto;margin-top:14px}.tabs button{background:none;border:0;border-bottom:2px solid transparent;border-radius:0;color:var(--muted);padding:9px 14px;font:inherit;white-space:nowrap;cursor:pointer}
            .tabs button[aria-selected=true]{color:var(--ink);border-bottom-color:var(--accent)}.tabs button:hover{color:var(--ink)}.tabs button:focus-visible{outline:2px solid var(--accent);outline-offset:-2px}
            .tabs .count{display:inline-block;min-width:18px;margin-left:6px;padding:0 6px;border-radius:9px;background:#3b2a20;color:var(--warn);font-size:12px;text-align:center}
            .panel>h2:first-child,.panel>section:first-child>h2:first-of-type{margin-top:18px}
            .js .panel[hidden]{display:none}
            .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin:18px 0}
            .kpi{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 16px}.kpi b{display:block;font-size:24px;font-weight:600;margin:2px 0}.kpi small{color:var(--muted)}
            .kpi.attention{border-color:#6b4b2a}.kpi.attention b{color:var(--warn)}
            .cast{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:12px}
            .cast article{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 16px;min-width:0;overflow-wrap:anywhere}
            .cast h3{margin:0 0 6px;font-size:13px;color:var(--muted);font-weight:500;text-transform:uppercase;letter-spacing:.6px}
            .cast .who{font-size:16px;font-weight:600}.cast .meta{color:var(--muted);font-size:13px}.cast .reserve{margin-top:8px;font-size:12px;color:var(--muted)}
            .chip{display:inline-block;border:1px solid var(--line);border-radius:999px;padding:1px 9px;font-size:12px;color:var(--muted);white-space:nowrap}
            .chip.passed,.chip.completed,.chip.verified{color:var(--ok);border-color:#2f5a4d}.chip.failed,.chip.unverified{color:var(--bad);border-color:#6a3a2f}
            .chip.running,.chip.pending{color:var(--warn);border-color:#6b5a2a}.chip.skipped,.chip.unknown,.chip.not_reached,.chip.earlier_candidate{color:var(--idle)}
            .recent{list-style:none;padding:0;margin:0;border:1px solid var(--line);border-radius:10px;overflow:hidden}
            .recent li{display:grid;grid-template-columns:minmax(0,2fr) minmax(0,1.4fr) minmax(160px,1fr) auto;gap:12px;align-items:center;padding:10px 14px;border-bottom:1px solid var(--line)}
            .recent li:last-child{border:0}.recent .id{font-weight:600;overflow-wrap:anywhere}.recent .sub{color:var(--muted);font-size:12px}
            .strip{display:flex;gap:3px;min-width:0}.strip i{flex:1;height:8px;border-radius:2px;background:var(--idle);min-width:6px}
            .strip i.passed{background:var(--ok)}.strip i.failed{background:var(--bad)}.strip i.running{background:var(--warn)}.strip i.skipped,.strip i.unknown{background:#3a4955}
            #run-detail{scroll-margin-top:120px;background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:18px 20px;margin:18px 0}
            #run-detail header{display:flex;flex-wrap:wrap;gap:10px 18px;align-items:baseline}#run-detail header h3{margin:0;font-size:18px;overflow-wrap:anywhere}
            .facts{display:flex;flex-wrap:wrap;gap:8px 22px;margin:10px 0;color:var(--muted)}.facts b{color:var(--ink);font-weight:600}
            .stages{display:flex;flex-wrap:wrap;gap:6px;margin:12px 0}.note{border-left:3px solid var(--line);padding:4px 12px;color:var(--muted);margin:10px 0;white-space:pre-wrap}
            ol.tl{list-style:none;margin:14px 0 0;padding:0 0 0 22px;border-left:2px solid var(--line)}
            ol.tl>li{position:relative;padding:0 0 18px 10px}ol.tl>li::before{content:"";position:absolute;left:-31px;top:4px;width:14px;height:14px;border-radius:50%;background:var(--idle);border:3px solid var(--bg)}
            ol.tl>li.passed::before{background:var(--ok)}ol.tl>li.failed::before{background:var(--bad)}ol.tl>li.running::before{background:var(--warn)}
            .tl .head{display:flex;flex-wrap:wrap;gap:6px 12px;align-items:baseline}.tl .head strong{font-size:15px}.tl .nums{margin-left:auto;color:var(--muted);font-variant-numeric:tabular-nums}
            .tl .who{color:var(--muted);font-size:13px;overflow-wrap:anywhere}.tl .code{color:var(--bad);font-size:13px}
            .tl table{width:auto;margin:6px 0;font-size:13px}.tl td,.tl th{padding:4px 10px}.tl td:first-child{font-weight:400}
            .evidence{display:flex;flex-wrap:wrap;gap:8px;margin-top:6px;align-items:flex-start}.evidence a{font-size:13px}
            .evidence img{display:block;width:150px;height:auto;border:1px solid var(--line);border-radius:6px;background:#0b1015}
            .attr{font-size:12px;border-radius:4px;padding:1px 6px}.attr.regression{background:#4a2420;color:var(--bad)}.attr.pre_existing{background:#3b3420;color:var(--warn)}.attr.no_baseline{background:#26323c;color:var(--muted)}
            .linkish{background:none;border:0;color:var(--accent);padding:0;text-decoration:underline;cursor:pointer;font:inherit}
            @media(max-width:760px){body{padding:0 14px 40px}.recent li{grid-template-columns:1fr}.tl .nums{margin-left:0}}
            @media(prefers-reduced-motion:no-preference){.tabs button{transition:color .15s,border-color .15s}}
            """;

    static final String HEADER = """
            <header class="topbar"><div class="brand"><h1>AI Overseer <span>{{t:shell.dashboard}}</span></h1><p id="path" class="muted"></p>
            <div class="topbar-side"><label class="lang">{{t:lang.label}} <select id="lang-switch"><option value="en">{{t:lang.en}}</option><option value="ru">{{t:lang.ru}}</option></select></label>
            <div id="connection" role="status">{{t:shell.connecting}}</div></div></div>
            <nav class="tabs" role="tablist" aria-label="{{t:shell.dashboard_sections}}">
            <button role="tab" id="tab-overview" aria-controls="panel-overview" aria-selected="true">{{t:shell.overview}}</button>
            <button role="tab" id="tab-launch" aria-controls="panel-launch" aria-selected="false" tabindex="-1">{{t:launch.tab}}</button>
            <button role="tab" id="tab-runs" aria-controls="panel-runs" aria-selected="false" tabindex="-1">{{t:dash.runs}}</button>
            <button role="tab" id="tab-next" aria-controls="panel-next" aria-selected="false" tabindex="-1">{{t:dash.next_run}}</button>
            <button role="tab" id="tab-roles" aria-controls="panel-roles" aria-selected="false" tabindex="-1">{{t:shell.roles_and_vendors}}</button>
            <button role="tab" id="tab-analytics" aria-controls="panel-analytics" aria-selected="false" tabindex="-1">{{t:shell.analytics}}</button>
            <button role="tab" id="tab-connections" aria-controls="panel-connections" aria-selected="false" tabindex="-1">{{t:shell.connections}}</button>
            </nav></header>
            <div id="credential-warning" class="banner-warning" role="alert" hidden><b>{{t:cred.title}}</b> <span id="credential-warning-list"></span><p>{{t:cred.fix}}</p></div>
            """;

    static final String OVERVIEW = """
            <div class="kpis" id="overview-kpis">
            <div class="kpi" id="kpi-decisions"><small>{{t:dash.awaiting_your_decision}}</small><b id="kpi-decisions-value">—</b><small id="kpi-decisions-note"></small></div>
            <div class="kpi"><small>{{t:shell.currently_running}}</small><b id="kpi-active-value">—</b><small id="kpi-active-note"></small></div>
            <div class="kpi"><small>{{t:shell.latest_run}}</small><b id="kpi-last-value">—</b><small id="kpi-last-note"></small></div>
            <div class="kpi"><small>{{t:shell.spending_across_listed_runs}}</small><b id="kpi-cost-value">—</b><small id="kpi-cost-note"></small></div>
            </div>
            <h2>{{t:shell.who_works_on_the_next}}</h2>
            <p class="muted">{{t:shell.overview_lineup_note}}</p>
            <div class="cast" id="overview-cast"></div>
            <h2>{{t:shell.recent_runs}}</h2>
            <ol class="recent" id="overview-recent"></ol><p id="overview-recent-empty" class="empty" hidden>{{t:shell.no_runs_yet_start_with}} <code>oseer run &lt;task&gt; --dry-run</code>.</p>
            """;

    static final String RUN_DETAIL = """
            <section id="run-detail" aria-labelledby="run-detail-title" hidden tabindex="-1">
            <header><h3 id="run-detail-title">{{t:shell.run}}</h3><span id="run-detail-state" class="chip"></span>
            <button type="button" class="linkish" id="run-detail-close">{{t:shell.close}}</button></header>
            <div class="facts" id="run-detail-facts"></div>
            <div class="stages" id="run-detail-stages" aria-label="{{t:shell.workflow_stages}}"></div>
            <div id="run-detail-baseline"></div>
            <p id="run-detail-next" class="note" hidden></p>
            <ol class="tl" id="run-detail-steps"></ol>
            <p id="run-detail-status" class="muted" role="status" aria-live="polite"></p>
            </section>
            """;

    static final String SCRIPT = """
            (function(){const sw=document.getElementById('lang-switch');if(!sw)return;sw.value=document.documentElement.lang||'en';sw.addEventListener('change',()=>{document.cookie='oseer_lang='+encodeURIComponent(sw.value)+';path=/;max-age=31536000;samesite=strict';location.reload()})})();
            (()=>{'use strict';document.documentElement.classList.add('js');
            const el=id=>document.getElementById(id),pageToken=document.querySelector('meta[name=oseer-token]').content;
            const make=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n};
            const panels=['overview','launch','runs','next','roles','analytics','connections'];
            const legacy={'next-run':'next','personal-settings':'roles','analytics':'analytics','runs':'runs','model-settings':'roles','api-auth-settings':'connections'};
            function show(name,focus){if(!panels.includes(name))name='overview';for(const p of panels){const on=p===name;el('panel-'+p).hidden=!on;const tab=el('tab-'+p);tab.setAttribute('aria-selected',on);tab.tabIndex=on?0:-1;if(on&&focus)tab.focus()}
            if(name==='connections'){const d=el('api-auth-settings');if(d)d.open=true}}
            function route(){let hash;try{hash=decodeURIComponent(location.hash.slice(1))}catch(e){show('overview');return}if(hash.startsWith('run/')){show('runs');const [rid,jid]=hash.slice(4).split('@');openRun(rid,jid);return}
            if(hash.startsWith('tab-')){show(hash.slice(4));return}const target=hash&&document.getElementById(hash);
            if(legacy[hash]){show(legacy[hash]);if(target)target.scrollIntoView()}else if(target){const panel=target.closest('.panel');show(panel?panel.id.slice(6):'overview');target.scrollIntoView()}else show('overview')}
            for(const p of panels)el('tab-'+p).addEventListener('click',()=>{history.replaceState(null,'','#tab-'+p);show(p)});
            document.querySelector('.tabs').addEventListener('keydown',e=>{const i=panels.findIndex(p=>el('tab-'+p).getAttribute('aria-selected')==='true');
            let next=null;if(e.key==='ArrowRight')next=(i+1)%panels.length;else if(e.key==='ArrowLeft')next=(i-1+panels.length)%panels.length;else if(e.key==='Home')next=0;else if(e.key==='End')next=panels.length-1;
            if(next==null)return;e.preventDefault();history.replaceState(null,'','#tab-'+panels[next]);show(panels[next],true)});
            addEventListener('hashchange',route);
            const statusText={passed:'{{t:shell.passed}}',failed:'{{t:shell.failed}}',running:'{{t:shell.in_progress}}',skipped:'{{t:shell.skipped}}',unknown:'{{t:next.unknown}}',completed:'{{t:shell.completed}}',pending:'{{t:shell.still_open}}',not_reached:'{{t:shell.not_reached}}',earlier_candidate:'{{t:shell.earlier_candidate}}',finished:'{{t:shell.completed_2}}',stopped:'{{t:shell.stopped_without_results}}',no_summary:'{{t:shell.no_results}}',controller_stopped:'{{t:dash.controller_stopped_agent_unchecked}}'};
            const roleText={implementer:'{{t:shell.implementer}}',reviewer:'{{t:shell.review}}',planner:'{{t:shell.planning}}',plan_reviewer:'{{t:shell.plan_review}}',visual_qa:'{{t:shell.visual_qa}}'};
            const money=v=>costText(v,0);
            function duration(ms){if(ms==null)return '—';if(ms<1000)return Math.round(ms)+' {{t:shell.ms}}';if(ms<10000)return (ms/1000).toFixed(1).replace('.',',')+' {{t:next.s}}';const s=Math.round(ms/1000);if(s<60)return s+' {{t:next.s}}';const m=Math.floor(s/60);if(m<60)return m+' {{t:shell.min}} '+(s%60)+' {{t:next.s}}';return Math.floor(m/60)+' {{t:shell.h}} '+(m%60)+' {{t:shell.min}}'}
            function stepStatus(s){return s.status??(s.ok===true?'passed':s.ok===false?'failed':'unknown')}
            function primaryOf(rows){const byStage=new Map();for(const r of rows){if(!byStage.has(r.stage))byStage.set(r.stage,[]);byStage.get(r.stage).push(r)}return byStage}
            function drawCast(){const box=el('overview-cast');box.replaceChildren();const plan=data.plan||[];
            if(!plan.length){box.append(make('p','{{t:shell.personal_policy_has_not_been}}','muted'));return}
            for(const [stage,rows] of primaryOf(plan)){const pos=rows[0].strategy==='rotate'&&rows[0].position>=0?rows[0].position%rows.length:0;const main=rows[pos]??rows[0];
            const card=make('article');card.append(make('h3',stage+' · '+(roleText[main.role]??main.role)));card.append(make('div',main.profile,'who'));
            card.append(make('div',[main.vendor,main.model??(main.runner==='orca'?'{{t:shell.agent_model}}':'{{t:dash.default_model}}'),main.effort?('effort '+main.effort):null,main.runner].filter(Boolean).join(' · '),'meta'));
            const chips=make('div');chips.style.marginTop='8px';chips.append(make('span',main.verified?'{{t:shell.eligibility_confirmed}}':'{{t:shell.not_verified}}',main.verified?'chip verified':'chip unverified'));
            if(main.strategy==='rotate')chips.append(document.createTextNode(' '),make('span','{{t:shell.rotation}}','chip'));card.append(chips);
            const rest=rows.filter(r=>r!==main).map(r=>r.profile);if(rest.length)card.append(make('div','{{t:shell.next_in_line}} '+rest.join(', '),'reserve'));box.append(card)}}
            function drawKpis(){const pending=data.pending_decisions||[],runs=data.runs||[],active=(data.attempts||[]).filter(a=>a.view_state==='running');
            el('kpi-decisions-value').textContent=pending.length;el('kpi-decisions').classList.toggle('attention',pending.length>0);el('kpi-decisions-note').textContent=pending.length?'{{t:shell.list_and_buttons_further_down}}':'{{t:shell.no_open_questions}}';
            const badge=el('tab-overview');let count=badge.querySelector('.count');if(pending.length){if(!count){count=make('span',null,'count');badge.append(count)}count.textContent=pending.length}else if(count)count.remove();
            el('kpi-active-value').textContent=active.length;el('kpi-active-note').textContent=active.length?active.map(a=>(a.stage??a.role)+' · '+(a.vendor??a.profile)).join(', '):'{{t:shell.no_agents_running}}';
            const last=runs[0];el('kpi-last-value').textContent=last?runLabel(last):'—';el('kpi-last-note').textContent=last?(last.task_id??last.run_id)+(last.reason?' · '+last.reason:''):'{{t:shell.no_runs_yet}}';
            let cost=0,unpriced=0;for(const r of runs){cost+=Number(r.total_cost_usd??0);unpriced+=Number(r.unpriced_calls??0)}
            el('kpi-cost-value').textContent=runs.length?(unpriced>0&&cost===0?costText(0,unpriced):money(cost)):'—';el('kpi-cost-note').textContent=runs.length?(runs.length+' {{t:shell.run_s}}'+(unpriced?' · '+unpriced+' {{t:shell.unpriced_call_s_total_is}}':' {{t:shell.all_calls_priced}}')):''}
            function runLabel(r){if(r.state==='running')return '{{t:shell.in_progress}}';if(r.state==='controller_stopped')return '{{t:dash.controller_stopped_agent_unchecked}}';if(r.state==='no_summary')return '{{t:shell.no_results}}';if(r.ok===true)return '{{t:shell.ready_for_decision}}';if(r.ok===false)return '{{t:shell.stopped}}';return '—'}
            function strip(steps){const s=make('div',null,'strip');s.setAttribute('aria-hidden','true');for(const step of steps||[]){const i=make('i',null,stepStatus(step));i.title=(step.stage??step.step)+' · '+(statusText[stepStatus(step)]??'');s.append(i)}return s}
            function drawRecent(){const list=el('overview-recent');list.replaceChildren();const runs=(data.runs||[]).slice(0,8);el('overview-recent-empty').hidden=runs.length>0;list.hidden=!runs.length;
            for(const r of runs){const li=make('li');const id=make('div');id.append(make('div',r.task_id??'—','id'),make('div',r.run_id,'sub'));
            const label=runLabel(r);const cls=r.state==='running'?'chip running':r.ok===true?'chip passed':r.ok===false?'chip failed':'chip unknown';
            const verdict=make('div');verdict.append(make('span',label,cls),make('div',r.state==='running'?'{{t:shell.agent_running}}':(r.reason??''),'sub'));
            const open=make('button','{{t:dash.timeline}}');open.type='button';open.addEventListener('click',()=>{location.hash='run/'+encodeURIComponent(r.run_id)});
            li.append(id,verdict,strip(r.steps),open);list.append(li)}}
            let detailRun=null,detailJob=null,detailTimer=null,detailGeneration=0;
            function evidenceLink(dir,file){return '/evidence/'+encodeURIComponent(detailRun)+'/'+encodeURIComponent(dir)+'/'+file.path.split('/').map(encodeURIComponent).join('/')+'?t='+encodeURIComponent(pageToken)+(detailJob?'&job='+encodeURIComponent(detailJob):'')}
            const evidenceText={prompt:'{{t:shell.prompt}}',stdout:'{{t:shell.agent_output}}',stderr:'stderr',artifact:'{{t:shell.artifact}}',report:'{{t:shell.report}}',log:'{{t:shell.log}}',screenshot:'{{t:shell.screenshot}}'};
            function drawEvidence(parent,dir,files){if(!dir||!files||!files.length)return;const box=make('div',null,'evidence');let shots=0;
            for(const f of files){const a=make('a');a.href=evidenceLink(dir,f);a.target='_blank';a.rel='noopener';
            if(f.kind==='screenshot'&&shots<6){shots++;const img=make('img');img.loading='lazy';img.src=a.href;img.alt='{{t:shell.screenshot_2}} '+f.path.split('/').pop();a.append(img)}
            else a.textContent=(evidenceText[f.kind]??f.kind)+': '+f.path.split('/').pop();box.append(a)}parent.append(box)}
            function commandsTable(parent,commands){if(!commands||!commands.length)return;const t=make('table');const head=make('tr');for(const h of ['{{t:shell.command}}','{{t:shell.code}}','{{t:dash.time}}','{{t:shell.reason}}'])head.append(make('th',h));t.append(head);
            const attr={regression:'{{t:shell.regression_passed_before_the_task}}',pre_existing:'{{t:shell.failed_before_the_task}}',no_baseline:'{{t:shell.no_pre_task_record}}'};
            for(const c of commands){const tr=make('tr');tr.append(make('td',c.command??'—'),make('td',c.timed_out?'timeout':String(c.exit_code??'—')),make('td',duration(c.duration_ms)));
            const td=make('td');if(c.attribution)td.append(make('span',attr[c.attribution]??c.attribution,'attr '+c.attribution));else td.textContent=c.ok?'ok':'—';tr.append(td);t.append(tr)}parent.append(t)}
            function drawDetail(d){el('run-detail-title').textContent=(d.task_id??'—')+' · '+d.run_id;const state=el('run-detail-state');
            const causes=(d.stop_causes||[]).map(c=>[c.stage,c.profile,c.http_status?'HTTP '+c.http_status:null,c.code].filter(Boolean).join(' · ')).join(' → ');
            const verdict=d.state==='finished'?(d.ok===true?'{{t:shell.ready_for_decision}}':'{{t:shell.stopped}}: '+(causes||d.reason||'—')):statusText[d.state]??d.state;state.textContent=verdict;state.className='chip '+(d.state==='running'?'running':d.ok===true?'passed':d.ok===false?'failed':'unknown');
            const facts=el('run-detail-facts');facts.replaceChildren();const fact=(k,v)=>{const s=make('span',k+' ');s.append(make('b',v));facts.append(s)};const t=d.totals||{};
            fact('{{t:dash.time}}',duration(t.duration_ms)+(t.duration_complete?'':' {{t:shell.incomplete}}'));fact('{{t:dash.cost}}',costText(t.cost_usd_known,t.unpriced_calls??t.unpriced_steps)+(t.may_be_billed?' · {{t:dash.may_be_billed}}':''));
            if(d.mode)fact('{{t:next.mode}}',d.mode);if(d.risk)fact('{{t:dash.risk}}',d.risk);if(d.role_runs!=null)fact('{{t:shell.role_calls}}',String(d.role_runs));if(d.open_blocking_findings!=null)fact('{{t:dash.blockers}}',String(d.open_blocking_findings));if(Array.isArray(d.unverified_findings)&&d.unverified_findings.length)fact('{{t:shell.unverified_findings}}',String(d.unverified_findings.length));
            const dr=d.decision_record;if(dr)fact('{{t:dash.decision}}',(dr.state==='superseded'?'{{t:dash.decision_superseded}}':(dr.state??'—'))+(dr.decision?' · '+dr.decision:'')+(dr.actor?' · '+dr.actor:''));
            const stages=el('run-detail-stages');stages.replaceChildren();for(const s of d.workflow||[]){const c=make('span',s.stage+(s.state?' · '+(statusText[s.state]??s.state):''),'chip '+(s.state??''));if(s.reason)c.title=s.reason;stages.append(c)}
            const base=el('run-detail-baseline');base.replaceChildren();const b=d.baseline||{};
            if(b.required){const p=make('p',null,'note');p.textContent='{{t:shell.pre_task_baseline}} '+(b.baseline_ok===true?'{{t:shell.passing}}':b.baseline_ok===false?'{{t:shell.failing_no_vendors_were_called}}':'{{t:shell.not_recorded}}')+(b.baseline_reused_from?' {{t:shell.reused_from}} '+b.baseline_reused_from+')':'')+(b.declared?' · '+b.declared.join(', '):'')+'{{t:shell.a_failing_check_that_passed}}';base.append(p)}
            else if(d.state==='finished'){base.append(make('p','{{t:shell.no_baseline_declared_defaults_baseline}}','note'))}
            const next=el('run-detail-next');next.hidden=!d.next_step;next.textContent=d.next_step?'{{t:shell.next_step}} '+d.next_step:'';
            const list=el('run-detail-steps');list.replaceChildren();
            for(const s of d.steps||[]){const st=stepStatus(s);const li=make('li',null,st);const head=make('div',null,'head');head.append(make('strong',s.stage??s.step??'—'),make('span',s.kind==='gate'?'{{t:shell.check}}':'{{t:shell.role}}','chip'),make('span',statusText[st]??st,'chip '+st));
            if(s.reused)head.append(make('span','{{t:shell.verdict_reused_from}} '+s.reused_from,'chip'));
            const cost=s.cost_usd!=null||s.unpriced_calls?costText(s.cost_usd,s.unpriced_calls):'';head.append(make('span',[duration(s.duration_ms),cost,s.vendor_attempts>1?s.vendor_attempts+' {{t:shell.attempts}}':''].filter(Boolean).join(' · '),'nums'));li.append(head);
            const who=[s.role?roleText[s.role]??s.role:null,s.profile,s.vendor,s.model,s.effort?('effort '+s.effort):null,s.runner].filter(Boolean);if(who.length)li.append(make('div',who.join(' · '),'who'));
            if(s.tokens)li.append(make('div','{{t:shell.tokens}} '+(s.tokens.input??'—')+' in · '+(s.tokens.output??'—')+' out','who'));
            if(st==='failed'&&s.code&&s.code!=='ok')li.append(make('div','{{t:shell.code_2}} '+s.code+(s.http_status?' · HTTP '+s.http_status:''),'code'));for(const f of s.failed_attempts||[])li.append(make('div','{{t:timeline.attempt_failed}} '+[f.profile,f.vendor,f.http_status?'HTTP '+f.http_status:null,f.code,f.failure_phase,f.duration_ms!=null?duration(f.duration_ms):null,f.may_be_billed?'{{t:dash.may_be_billed}}':null].filter(Boolean).join(' · '),'code'));for(const f of s.failovers||[])li.append(make('div','{{t:timeline.switched}} '+(f.from_profile||'?')+' → '+(f.to_profile||'?')+(f.cause?' · '+f.cause:'')+(f.authorized_by?' · '+f.authorized_by:''),'muted'));if(s.blocking_findings)li.append(make('div','{{t:shell.blocking_findings}} '+s.blocking_findings,'code'));
            if(s.scope_violations)li.append(make('div','{{t:shell.scope_violations}} '+s.scope_violations,'code'));commandsTable(li,s.commands);drawEvidence(li,s.evidence_dir,s.evidence);list.append(li)}
            if(!(d.steps||[]).length)list.append(make('li','{{t:shell.no_steps_yet}}','unknown'));
            el('run-detail-status').textContent=(unsettled(d)?'{{t:shell.refreshes_every_2_seconds}} ':'')+'{{t:next.read}} '+new Date(d.observed_at).toLocaleTimeString()}
            const unsettled=d=>d.state==='running'||d.state==='no_summary';
            async function loadRun(){const generation=detailGeneration,id=detailRun;if(!id)return;
            try{const r=await fetch('/api/runs/'+encodeURIComponent(id)+(detailJob?'?job='+encodeURIComponent(detailJob):''),{cache:'no-store'});if(generation!==detailGeneration)return;if(!r.ok){el('run-detail-status').textContent=r.status===404?'{{t:shell.run_not_found}}':'{{t:shell.run_data_unavailable}}';return}
            const d=await r.json();if(generation!==detailGeneration)return;drawDetail(d);if(unsettled(d))detailTimer=setTimeout(loadRun,2000)}
            catch(e){if(generation===detailGeneration){el('run-detail-status').textContent='{{t:shell.cannot_reach_the_dashboard}}';detailTimer=setTimeout(loadRun,4000)}}}
            function openRun(id,job){if(!id)return;clearTimeout(detailTimer);detailGeneration++;detailRun=id;detailJob=job||null;const box=el('run-detail');box.hidden=false;el('run-detail-steps').replaceChildren();el('run-detail-status').textContent='{{t:shell.loading}}';loadRun();box.focus({preventScroll:true});box.scrollIntoView({block:'start'})}
            el('run-detail-close').addEventListener('click',()=>{clearTimeout(detailTimer);detailGeneration++;detailRun=null;el('run-detail').hidden=true;history.replaceState(null,'','#tab-runs')});
            window.overseerOpenRun=(id,job)=>{location.hash='run/'+encodeURIComponent(id)+(job?'@'+encodeURIComponent(job):'')};
            function drawCredentialWarning(){const box=document.getElementById('credential-warning');const rows=data.credential_warnings||[];box.hidden=!rows.length;document.getElementById('credential-warning-list').textContent=rows.map(r=>r.credential_ref+' ('+r.connection+' · '+r.state+' · '+(r.profiles||[]).join(', ')+')').join('; ')}
            window.overseerShellDraw=()=>{if(!data)return;drawCredentialWarning();drawKpis();drawCast();drawRecent()};
            route()})();
            """;
}
