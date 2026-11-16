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
            .chip.running,.chip.pending{color:var(--warn);border-color:#6b5a2a}.chip.skipped,.chip.unknown{color:var(--idle)}
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
            <header class="topbar"><div class="brand"><h1>AI Overseer <span>/ панель</span></h1><p id="path" class="muted"></p>
            <div id="connection" role="status">Подключение…</div></div>
            <nav class="tabs" role="tablist" aria-label="Разделы панели">
            <button role="tab" id="tab-overview" aria-controls="panel-overview" aria-selected="true">Обзор</button>
            <button role="tab" id="tab-runs" aria-controls="panel-runs" aria-selected="false" tabindex="-1">Запуски</button>
            <button role="tab" id="tab-next" aria-controls="panel-next" aria-selected="false" tabindex="-1">Следующий запуск</button>
            <button role="tab" id="tab-roles" aria-controls="panel-roles" aria-selected="false" tabindex="-1">Роли и вендоры</button>
            <button role="tab" id="tab-analytics" aria-controls="panel-analytics" aria-selected="false" tabindex="-1">Аналитика</button>
            <button role="tab" id="tab-connections" aria-controls="panel-connections" aria-selected="false" tabindex="-1">Подключения</button>
            </nav></header>
            """;

    static final String OVERVIEW = """
            <div class="kpis" id="overview-kpis">
            <div class="kpi" id="kpi-decisions"><small>Ждут вашего решения</small><b id="kpi-decisions-value">—</b><small id="kpi-decisions-note"></small></div>
            <div class="kpi"><small>Сейчас работают</small><b id="kpi-active-value">—</b><small id="kpi-active-note"></small></div>
            <div class="kpi"><small>Последний запуск</small><b id="kpi-last-value">—</b><small id="kpi-last-note"></small></div>
            <div class="kpi"><small>Расход по списку запусков</small><b id="kpi-cost-value">—</b><small id="kpi-cost-note"></small></div>
            </div>
            <h2>Кто работает в следующем запуске</h2>
            <p class="muted">По личной политике, до настроек конкретной задачи: <code>use:</code> задачи, флаги CLI и Jev могут выбрать другого участника. Точный состав для задачи — во вкладке «Следующий запуск». Менять участников и модели — во вкладке «Роли и вендоры».</p>
            <div class="cast" id="overview-cast"></div>
            <h2>Последние запуски</h2>
            <ol class="recent" id="overview-recent"></ol><p id="overview-recent-empty" class="empty" hidden>Запусков пока нет. Начните с <code>oseer run &lt;task&gt; --dry-run</code>.</p>
            """;

    static final String RUN_DETAIL = """
            <section id="run-detail" aria-labelledby="run-detail-title" hidden tabindex="-1">
            <header><h3 id="run-detail-title">Запуск</h3><span id="run-detail-state" class="chip"></span>
            <button type="button" class="linkish" id="run-detail-close">Закрыть</button></header>
            <div class="facts" id="run-detail-facts"></div>
            <div class="stages" id="run-detail-stages" aria-label="Стадии workflow"></div>
            <div id="run-detail-baseline"></div>
            <p id="run-detail-next" class="note" hidden></p>
            <ol class="tl" id="run-detail-steps"></ol>
            <p id="run-detail-status" class="muted" role="status" aria-live="polite"></p>
            </section>
            """;

    static final String SCRIPT = """
            (()=>{'use strict';document.documentElement.classList.add('js');
            const el=id=>document.getElementById(id),pageToken=document.querySelector('meta[name=oseer-token]').content;
            const make=(tag,text,cls)=>{const n=document.createElement(tag);if(text!=null)n.textContent=text;if(cls)n.className=cls;return n};
            const panels=['overview','runs','next','roles','analytics','connections'];
            const legacy={'next-run':'next','personal-settings':'roles','analytics':'analytics','runs':'runs','model-settings':'roles','api-auth-settings':'connections'};
            function show(name,focus){if(!panels.includes(name))name='overview';for(const p of panels){const on=p===name;el('panel-'+p).hidden=!on;const tab=el('tab-'+p);tab.setAttribute('aria-selected',on);tab.tabIndex=on?0:-1;if(on&&focus)tab.focus()}
            if(name==='connections'){const d=el('api-auth-settings');if(d)d.open=true}}
            function route(){const hash=decodeURIComponent(location.hash.slice(1));if(hash.startsWith('run/')){show('runs');openRun(hash.slice(4));return}
            if(hash.startsWith('tab-')){show(hash.slice(4));return}const target=hash&&document.getElementById(hash);
            if(legacy[hash]){show(legacy[hash]);if(target)target.scrollIntoView()}else if(target){const panel=target.closest('.panel');show(panel?panel.id.slice(6):'overview');target.scrollIntoView()}else show('overview')}
            for(const p of panels)el('tab-'+p).addEventListener('click',()=>{history.replaceState(null,'','#tab-'+p);show(p)});
            document.querySelector('.tabs').addEventListener('keydown',e=>{const i=panels.findIndex(p=>el('tab-'+p).getAttribute('aria-selected')==='true');
            let next=null;if(e.key==='ArrowRight')next=(i+1)%panels.length;else if(e.key==='ArrowLeft')next=(i-1+panels.length)%panels.length;else if(e.key==='Home')next=0;else if(e.key==='End')next=panels.length-1;
            if(next==null)return;e.preventDefault();history.replaceState(null,'','#tab-'+panels[next]);show(panels[next],true)});
            addEventListener('hashchange',route);
            const statusText={passed:'пройдено',failed:'не пройдено',running:'идёт',skipped:'пропущено',unknown:'неизвестно',completed:'завершена',pending:'не закрыта',finished:'завершён',stopped:'остановлен без итогов',no_summary:'без итогов'};
            const roleText={implementer:'исполнитель',reviewer:'ревью',planner:'планирование',plan_reviewer:'ревью плана',visual_qa:'визуальная проверка'};
            const money=v=>v==null?'—':'$'+Number(v).toFixed(Number(v)<1?4:2);
            function duration(ms){if(ms==null)return '—';const s=Math.round(ms/1000);if(s<60)return s+' с';const m=Math.floor(s/60);if(m<60)return m+' мин '+(s%60)+' с';return Math.floor(m/60)+' ч '+(m%60)+' мин'}
            function stepStatus(s){return s.status??(s.ok===true?'passed':s.ok===false?'failed':'unknown')}
            function primaryOf(rows){const byStage=new Map();for(const r of rows){if(!byStage.has(r.stage))byStage.set(r.stage,[]);byStage.get(r.stage).push(r)}return byStage}
            function drawCast(){const box=el('overview-cast');box.replaceChildren();const plan=data.plan||[];
            if(!plan.length){box.append(make('p','Личная политика не прочитана или в ней нет ролей. Настройте роли во вкладке «Роли и вендоры».','muted'));return}
            for(const [stage,rows] of primaryOf(plan)){const pos=rows[0].strategy==='rotate'&&rows[0].position>=0?rows[0].position%rows.length:0;const main=rows[pos]??rows[0];
            const card=make('article');card.append(make('h3',stage+' · '+(roleText[main.role]??main.role)));card.append(make('div',main.profile,'who'));
            card.append(make('div',[main.vendor,main.model??(main.runner==='orca'?'модель агента':'модель по умолчанию'),main.effort?('effort '+main.effort):null,main.runner].filter(Boolean).join(' · '),'meta'));
            const chips=make('div');chips.style.marginTop='8px';chips.append(make('span',main.verified?'допуск подтверждён':'не проверен',main.verified?'chip verified':'chip unverified'));
            if(main.strategy==='rotate')chips.append(document.createTextNode(' '),make('span','ротация','chip'));card.append(chips);
            const rest=rows.filter(r=>r!==main).map(r=>r.profile);if(rest.length)card.append(make('div','Дальше по очереди: '+rest.join(', '),'reserve'));box.append(card)}}
            function drawKpis(){const pending=data.pending_decisions||[],runs=data.runs||[],active=(data.attempts||[]).filter(a=>a.view_state==='running');
            el('kpi-decisions-value').textContent=pending.length;el('kpi-decisions').classList.toggle('attention',pending.length>0);el('kpi-decisions-note').textContent=pending.length?'Список и кнопки — ниже на этой вкладке':'Нет открытых вопросов';
            const badge=el('tab-overview');let count=badge.querySelector('.count');if(pending.length){if(!count){count=make('span',null,'count');badge.append(count)}count.textContent=pending.length}else if(count)count.remove();
            el('kpi-active-value').textContent=active.length;el('kpi-active-note').textContent=active.length?active.map(a=>(a.stage??a.role)+' · '+(a.vendor??a.profile)).join(', '):'Агенты сейчас не запущены';
            const last=runs[0];el('kpi-last-value').textContent=last?(last.ok===true?'готов к решению':last.ok===false?'остановлен':'—'):'—';el('kpi-last-note').textContent=last?(last.task_id??last.run_id)+(last.reason?' · '+last.reason:''):'Запусков пока нет';
            let cost=0,unpriced=0;for(const r of runs){cost+=Number(r.total_cost_usd??0);unpriced+=Number(r.unpriced_calls??0)}
            el('kpi-cost-value').textContent=runs.length?money(cost):'—';el('kpi-cost-note').textContent=runs.length?(runs.length+' запуск(ов)'+(unpriced?' · '+unpriced+' вызов(ов) без цены — сумма неполная':' · все вызовы с ценой')):''}
            function strip(steps){const s=make('div',null,'strip');s.setAttribute('aria-hidden','true');for(const step of steps||[]){const i=make('i',null,stepStatus(step));i.title=(step.stage??step.step)+' · '+(statusText[stepStatus(step)]??'');s.append(i)}return s}
            function drawRecent(){const list=el('overview-recent');list.replaceChildren();const runs=(data.runs||[]).slice(0,6),open=(data.open_runs||[]).slice(0,4);el('overview-recent-empty').hidden=runs.length+open.length>0;list.hidden=!(runs.length+open.length);
            const live=new Set((data.attempts||[]).filter(a=>a.view_state==='running').map(a=>a.workflow_run_id));
            for(const r of open){const li=make('li');const id=make('div');id.append(make('div',r.task_id??'—','id'),make('div',r.run_id,'sub'));const going=live.has(r.run_id);
            const verdict=make('div');verdict.append(make('span',going?'идёт':'без итогов',going?'chip running':'chip unknown'),make('div',going?'агент работает':'итог не записан: процесс мог остановиться','sub'));
            const button=make('button','Хронология');button.type='button';button.addEventListener('click',()=>{location.hash='run/'+encodeURIComponent(r.run_id)});li.append(id,verdict,make('div'),button);list.append(li)}
            for(const r of runs){const li=make('li');const id=make('div');id.append(make('div',r.task_id??'—','id'),make('div',r.run_id,'sub'));
            const verdict=make('div');verdict.append(make('span',r.ok===true?'готов к решению':r.ok===false?'остановлен':'—',r.ok===true?'chip passed':r.ok===false?'chip failed':'chip'),make('div',r.reason??'','sub'));
            const open=make('button','Хронология');open.type='button';open.addEventListener('click',()=>{location.hash='run/'+encodeURIComponent(r.run_id)});
            li.append(id,verdict,strip(r.steps),open);list.append(li)}}
            let detailRun=null,detailTimer=null,detailGeneration=0;
            function evidenceLink(dir,file){return '/evidence/'+encodeURIComponent(detailRun)+'/'+encodeURIComponent(dir)+'/'+file.path.split('/').map(encodeURIComponent).join('/')+'?t='+encodeURIComponent(pageToken)}
            const evidenceText={prompt:'промпт',stdout:'вывод агента',stderr:'stderr',artifact:'артефакт',report:'отчёт',log:'лог',screenshot:'скриншот'};
            function drawEvidence(parent,dir,files){if(!dir||!files||!files.length)return;const box=make('div',null,'evidence');let shots=0;
            for(const f of files){const a=make('a');a.href=evidenceLink(dir,f);a.target='_blank';a.rel='noopener';
            if(f.kind==='screenshot'&&shots<6){shots++;const img=make('img');img.loading='lazy';img.src=a.href;img.alt='Скриншот '+f.path.split('/').pop();a.append(img)}
            else a.textContent=(evidenceText[f.kind]??f.kind)+': '+f.path.split('/').pop();box.append(a)}parent.append(box)}
            function commandsTable(parent,commands){if(!commands||!commands.length)return;const t=make('table');const head=make('tr');for(const h of ['Команда','Код','Время','Причина'])head.append(make('th',h));t.append(head);
            const attr={regression:'регрессия: до задачи было зелёным',pre_existing:'было красным до задачи',no_baseline:'нет записи до задачи'};
            for(const c of commands){const tr=make('tr');tr.append(make('td',c.command??'—'),make('td',c.timed_out?'timeout':String(c.exit_code??'—')),make('td',duration(c.duration_ms)));
            const td=make('td');if(c.attribution)td.append(make('span',attr[c.attribution]??c.attribution,'attr '+c.attribution));else td.textContent=c.ok?'ok':'—';tr.append(td);t.append(tr)}parent.append(t)}
            function drawDetail(d){el('run-detail-title').textContent=(d.task_id??'—')+' · '+d.run_id;const state=el('run-detail-state');
            const verdict=d.state==='finished'?(d.ok===true?'готов к решению':'остановлен'+(d.reason?': '+d.reason:'')):statusText[d.state]??d.state;state.textContent=verdict;state.className='chip '+(d.state==='running'?'running':d.ok===true?'passed':d.ok===false?'failed':'unknown');
            const facts=el('run-detail-facts');facts.replaceChildren();const fact=(k,v)=>{const s=make('span',k+' ');s.append(make('b',v));facts.append(s)};const t=d.totals||{};
            fact('Время',duration(t.duration_ms)+(t.duration_complete?'':' (неполное)'));fact('Стоимость',money(t.cost_usd_known)+(t.unpriced_steps?' + '+t.unpriced_steps+' без цены':''));
            if(d.mode)fact('Режим',d.mode);if(d.risk)fact('Риск',d.risk);if(d.role_runs!=null)fact('Вызовы ролей',String(d.role_runs));if(d.open_blocking_findings!=null)fact('Блокеры',String(d.open_blocking_findings));
            const dr=d.decision_record;if(dr)fact('Решение',(dr.state??'—')+(dr.decision?' · '+dr.decision:'')+(dr.actor?' · '+dr.actor:''));
            const stages=el('run-detail-stages');stages.replaceChildren();for(const s of d.workflow||[]){const c=make('span',s.stage+(s.state?' · '+(statusText[s.state]??s.state):''),'chip '+(s.state??''));if(s.reason)c.title=s.reason;stages.append(c)}
            const base=el('run-detail-baseline');base.replaceChildren();const b=d.baseline||{};
            if(b.required){const p=make('p',null,'note');p.textContent='Базовая линия до задачи: '+(b.baseline_ok===true?'зелёная':b.baseline_ok===false?'красная — вендоры не вызывались':'не записана')+(b.baseline_reused_from?' (переиспользована из '+b.baseline_reused_from+')':'')+(b.declared?' · '+b.declared.join(', '):'')+'. Красная проверка, которая была зелёной до задачи, отмечена как регрессия.';base.append(p)}
            else if(d.state==='finished'){base.append(make('p','Базовая линия не объявлена (defaults.baseline_checks): по красной проверке нельзя сказать, была ли она красной до задачи.','note'))}
            const next=el('run-detail-next');next.hidden=!d.next_step;next.textContent=d.next_step?'Следующий шаг: '+d.next_step:'';
            const list=el('run-detail-steps');list.replaceChildren();
            for(const s of d.steps||[]){const st=stepStatus(s);const li=make('li',null,st);const head=make('div',null,'head');head.append(make('strong',s.stage??s.step??'—'),make('span',s.kind==='gate'?'проверка':'роль','chip'),make('span',statusText[st]??st,'chip '+st));
            if(s.reused)head.append(make('span','вердикт переиспользован из '+s.reused_from,'chip'));
            const cost=s.cost_usd!=null?money(s.cost_usd):s.unpriced?'без цены':'';head.append(make('span',[duration(s.duration_ms),cost,s.vendor_attempts>1?s.vendor_attempts+' попытки':''].filter(Boolean).join(' · '),'nums'));li.append(head);
            const who=[s.role?roleText[s.role]??s.role:null,s.profile,s.vendor,s.model,s.effort?('effort '+s.effort):null,s.runner].filter(Boolean);if(who.length)li.append(make('div',who.join(' · '),'who'));
            if(s.tokens)li.append(make('div','токены: '+(s.tokens.input??'—')+' in · '+(s.tokens.output??'—')+' out','who'));
            if(st==='failed'&&s.code)li.append(make('div','код: '+s.code,'code'));if(s.blocking_findings)li.append(make('div','блокирующих замечаний: '+s.blocking_findings,'code'));
            if(s.scope_violations)li.append(make('div','нарушений scope: '+s.scope_violations,'code'));commandsTable(li,s.commands);drawEvidence(li,s.evidence_dir,s.evidence);list.append(li)}
            if(!(d.steps||[]).length)list.append(make('li','Шагов пока нет.','unknown'));
            el('run-detail-status').textContent=(d.state==='running'?'Обновляется каждые 2 секунды · ':'')+'Прочитано '+new Date(d.observed_at).toLocaleTimeString()}
            async function loadRun(){const generation=detailGeneration,id=detailRun;if(!id)return;
            try{const r=await fetch('/api/runs/'+encodeURIComponent(id),{cache:'no-store'});if(generation!==detailGeneration)return;if(!r.ok){el('run-detail-status').textContent=r.status===404?'Запуск не найден.':'Данные запуска недоступны.';return}
            const d=await r.json();if(generation!==detailGeneration)return;drawDetail(d);if(d.state==='running')detailTimer=setTimeout(loadRun,2000)}
            catch(e){el('run-detail-status').textContent='Нет связи с панелью.';detailTimer=setTimeout(loadRun,4000)}}
            function openRun(id){if(!id)return;clearTimeout(detailTimer);detailGeneration++;detailRun=id;const box=el('run-detail');box.hidden=false;el('run-detail-steps').replaceChildren();el('run-detail-status').textContent='Загрузка…';loadRun();box.focus({preventScroll:true});box.scrollIntoView({block:'start'})}
            el('run-detail-close').addEventListener('click',()=>{clearTimeout(detailTimer);detailGeneration++;detailRun=null;el('run-detail').hidden=true;history.replaceState(null,'','#tab-runs')});
            window.overseerOpenRun=id=>{location.hash='run/'+encodeURIComponent(id)};
            window.overseerShellDraw=()=>{if(!data)return;drawKpis();drawCast();drawRecent()};
            route()})();
            """;
}
