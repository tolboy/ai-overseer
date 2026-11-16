package dev.oseer.dashboard;

/** Same-origin, text-only controls; requests cannot carry filenames, commands or raw YAML. */
final class DashboardControls {
    private DashboardControls() {}
    static final String BUDGET_HTML = """
            <fieldset><legend>{{t:next.task_mode}}</legend><div class="next-fields">
            <label>{{t:next.mode}}<select id="task-mode" aria-describedby="task-mode-help"><option value="">{{t:next.not_set}}</option><option value="quick">{{t:next.quick_small_change}}</option><option value="bug">{{t:next.bug_bug_fix}}</option><option value="task">{{t:next.task_regular_task}}</option><option value="refactor">{{t:next.refactor_no_behavior_changes}}</option></select></label>
            </div><p id="task-mode-help" class="muted">{{t:next.mode_fills_only_what_the}}</p></fieldset>
            <fieldset><legend>{{t:next.task_budgets}}</legend><div class="next-fields">
            <label>{{t:next.calls_1_50}}<input id="limit-max_role_runs" type="number" min="1" max="50" step="1" required></label>
            <label>{{t:next.cost_usd_0_no_spending}}<input id="limit-max_cost_usd" type="number" min="0" max="10000" step="any" required></label>
            <label>{{t:next.cost_mode}}<select id="limit-cost_cap"><option value="threshold">{{t:next.threshold_on_known_spending}}</option><option value="strict">{{t:next.strict_cap_with_reservations}}</option></select></label>
            <label>{{t:next.chain_time_minutes_1_1440}}<input id="limit-max_elapsed_minutes" type="number" min="1" max="1440" step="1" placeholder="{{t:next.unlimited}}"></label>
            <label>{{t:next.fixes_0_10}}<input id="limit-max_fix_attempts" type="number" min="0" max="10" step="1"></label>
            <label>{{t:next.call_timeout_minutes_1_240}}<input id="limit-timeout_minutes" type="number" min="1" max="240" step="1"></label>
            <label>{{t:next.human_question_deadline_hours_1}}<input id="limit-gate_ttl_hours" type="number" min="1" max="720" step="1" placeholder="{{t:next.no_deadline}}"></label>
            </div><p class="muted">{{t:next.unpriced_costs_remain_unknown_a}}</p></fieldset>
            """;
    static final String HTML = """
            <section id="personal-settings" class="editor" aria-labelledby="personal-title">
            <p id="settings-receipt" role="status" aria-live="polite"></p>
            <h2 id="personal-title">{{t:next.personal_defaults}}</h2>
            <p class="muted">{{t:next.saved_in_your_personal_policy}}</p>
            <p id="personal-status" role="status" aria-live="polite">{{t:next.choose_a_task_to_check}}</p>
            <div id="personal-roles"></div>
            <fieldset><legend>{{t:next.retries_and_failovers}}</legend><div class="next-fields">
            <label>{{t:next.on_quota_exhaustion}}<select id="personal-on_quota_exhausted"><option value="confirm">{{t:next.ask}}</option><option value="auto">{{t:next.automatic}}</option><option value="stop">{{t:next.stop}}</option></select></label>
            <label>{{t:next.on_vendor_error}}<select id="personal-on_vendor_failure"><option value="stop">{{t:next.stop}}</option><option value="confirm">{{t:next.ask}}</option><option value="auto">{{t:next.automatic}}</option></select></label>
            <label>{{t:next.failovers_per_stage_0_8}}<input id="personal-max_per_stage" type="number" min="0" max="8" step="1" required></label>
            <label>{{t:next.rate_limit_retries_0_5}}<input id="personal-max_attempts" type="number" min="0" max="5" step="1" required></label>
            <label>{{t:next.initial_backoff_seconds_1_900}}<input id="personal-backoff_seconds" type="number" min="1" max="900" step="1" required></label>
            <label>{{t:next.fix_reserve}}<select id="personal-repair_reserve"><option value="full">{{t:next.full_remaining_cycle}}</option><option value="partial">{{t:next.fix_and_one_reviewer}}</option></select></label>
            </div><p class="muted">{{t:next.profile_order_determines_available_fallbacks}}</p></fieldset>
            <button id="personal-preview" type="button" disabled>{{t:next.check_policy_changes}}</button>
            <button id="personal-reload" type="button">{{t:next.reload_policy}}</button>
            <div id="personal-result" hidden><p id="personal-file" class="muted"></p><pre id="personal-diff"></pre>
            <pre id="personal-cast"></pre><pre id="personal-problems" class="bad"></pre>
            <button id="personal-apply" type="button" disabled>{{t:next.apply_personal_policy}}</button></div>
            </section>
            <section id="analytics" class="editor" aria-labelledby="analytics-title">
            <h2 id="analytics-title">{{t:next.results_analytics}}</h2>
            <p class="muted">{{t:next.local_attempt_logs_and_summaries}}</p>
            <div class="next-fields"><label>{{t:next.task_id_blank_all}}<input id="analytics-task" maxlength="160"></label><label>{{t:next.run_id_blank_all}}<input id="analytics-run" maxlength="160"></label></div>
            <button id="analytics-reload" type="button">{{t:next.refresh_analytics}}</button><p id="analytics-status" role="status" aria-live="polite"></p>
            <p id="analytics-warning" class="bad"></p><div id="analytics-cards" class="cards"></div>
            <h3>{{t:next.measurements_and_coverage}}</h3><div class="scroll" tabindex="0"><table><thead><tr><th>{{t:next.metric}}</th><th>{{t:next.known_total}}</th><th>{{t:next.known_unknown}}</th><th>p50 / p95</th></tr></thead><tbody id="analytics-metrics"></tbody></table></div>
            <h3>{{t:next.actual_attempt_parameters}}</h3><div class="scroll" tabindex="0"><table><thead><tr><th>{{t:next.vendor}}</th><th>{{t:next.requested_model}}</th><th>{{t:next.reported_model}}</th><th>{{t:next.requested_effort_runner}}</th><th>{{t:next.calls_errors}}</th><th>{{t:next.known_usd_coverage}}</th><th>{{t:next.time_p50_p95_ms}}</th></tr></thead><tbody id="analytics-settings"></tbody></table></div>
            <h3>{{t:next.run_results}}</h3><div class="scroll" tabindex="0"><table><thead><tr><th>{{t:dash.run_task}}</th><th>{{t:next.workflow_review_human}}</th><th>{{t:next.calls_known_usd}}</th><th>{{t:next.fixes_failover_backoff}}</th><th>{{t:next.blocker_history}}</th><th>{{t:next.chain}}</th></tr></thead><tbody id="analytics-runs"></tbody></table></div>
            <div id="analytics-details"></div>
            <p class="muted">{{t:next.call_time_is_the_measured}}</p>
            </section>
            """;

    static final String SCRIPT = """
            const limitKeys=['max_role_runs','max_cost_usd','cost_cap','max_elapsed_minutes','max_fix_attempts','timeout_minutes','gate_ttl_hours'];
            const personalKeys=['on_quota_exhausted','on_vendor_failure','max_per_stage','max_attempts','backoff_seconds','repair_reserve'];
            let taskEdit=null,personalEdit=null,personalData=null,personalGeneration=0,analyticsGeneration=0;
            async function settingsPost(path,request){const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-Overseer-Token':token},body:JSON.stringify(request)});const result=await response.json();
            if(!response.ok)throw Error(response.status===409?'{{t:next.settings_conflict}}'+(result.error??'config_conflict')+'{{t:next.reload_and_check_the_diff}}':result.message??result.error);return result}
            function clearTaskEdit(){taskEdit=null;$('task-apply').disabled=true;$('task-edit-diff').textContent='';$('task-edit-file').textContent='';$('task-save-status').textContent=''}
            const modeHelp={'':'{{t:next.mode_not_set_task_and}}',quick:'{{t:next.quick_low_risk_policy_usually}}',bug:'{{t:next.bug_requires_reproduce_a_command}}',task:'{{t:next.task_no_preset_same_as}}',refactor:'{{t:next.refactor_behavior_stays_the_same}}'};
            function drawModeHelp(){$('task-mode-help').textContent=modeHelp[$('task-mode').value]??''}
            function fillLimits(d){for(const key of limitKeys)$('limit-'+key).value=d[key]??'';$('task-mode').value=d.mode??'';drawModeHelp()}
            $('task-mode').addEventListener('change',()=>{drawModeHelp();clearNext();$('next-status').textContent='{{t:next.mode_changed_click_check_lineup}}';$('next-preview').disabled=false});
            for(const key of limitKeys)$('limit-'+key).addEventListener('input',()=>{clearNext();$('next-status').textContent='{{t:next.budget_changed_repeat_preview_before}}'});
            function taskChanges(){const use=Object.create(null),limits=Object.create(null);
            for(const field of $('next-stages').querySelectorAll('[data-next-stage]'))for(const control of field.querySelectorAll('[data-next-kind]')){const value=control.value.trim();if(value)(use[control.dataset.nextKind]??=Object.create(null))[field.dataset.nextStage]=value}
            for(const key of limitKeys){const input=$('limit-'+key);if(!input.reportValidity())throw Error('{{t:next.check_the_budget}} '+key);const v=input.value===''?null:key==='cost_cap'?input.value:Number(input.value);if(v!==nextData[key])limits[key]=v}
            const changes={use,limits},mode=$('task-mode').value||null;if(mode!==(nextData.mode??null))changes.mode=mode;return changes}
            function previewText(p){const plan=p.budget_plan;return '{{t:next.minimum_calls}} '+plan.minimum_success_calls+' · cap: '+plan.requested_cap+' {{t:next.reserve}} '+plan.repair_reserve+
            (plan.recovery_branches.length?'\\n{{t:next.fixes}}\\n'+plan.recovery_branches.map(b=>b.stage+'{{t:next.another}} '+b.calls_required_here+' {{t:next.calls}} '+(b.repair_allowed_under_cap?'{{t:next.fits}}':'{{t:next.does_not_fit}}')).join('\\n'):'')}
            function problemText(p){return (p.problems||[]).map(p=>(p.stage?p.stage+': ':'')+(p.code??p.stop_reason??'{{t:next.rejected}}')+' · '+(p.message??'')).join('\\n')}
            async function previewTask(){if(!nextData)return;clearNext();const generation=nextGeneration;$('next-preview').disabled=true;
            try{const request={task:nextData.task,revision:nextData.revision,target:'task',changes:taskChanges()},result=await settingsPost('/api/settings/preview',request);if(generation!==nextGeneration)return;
            taskEdit={...request,preview_sha256:result.preview_sha256};const p=result.preview,e=result.exports;
            $('next-cast').textContent=p.would_run.map(plannedDispatch).join('\\n');$('next-budget').textContent=previewText(p);$('next-problems').textContent=problemText(p);
            $('task-edit-file').textContent=result.file;$('task-edit-diff').textContent=result.diff;$('task-apply').disabled=!result.apply_allowed;
            $('next-dry-command').value=e?.dry_run_command??'';$('next-run-command').value=e?.run_command??'';const overlay=e?.task_overlay??'';$('next-yaml').value=/^\\s*(use:\\s*)?\\{\\}\\s*$/.test(overlay)?'':overlay;
            for(const button of document.querySelectorAll('[data-next-copy]'))button.disabled=!$(button.dataset.nextCopy).value;
            $('next-result').hidden=false;$('next-status').textContent=!p.settings_ok?'{{t:next.there_are_reasons_for_rejection}}':e?'{{t:next.lineup_and_budget_validated_you}}':'{{t:next.lineup_and_budget_validated_save}}';
            }catch(e){if(generation===nextGeneration)$('next-status').textContent=e.message}finally{if(nextData)$('next-preview').disabled=false}}
            $('task-apply').addEventListener('click',async()=>{if(!taskEdit)return;const edit=taskEdit;clearTaskEdit();$('next-preview').disabled=true;$('task-save-status').textContent='{{t:next.saving}}';
            try{const result=await settingsPost('/api/settings/apply',edit);$('settings-receipt').textContent='{{t:next.task_saved}} '+result.file+' · backup: '+result.backup;await loadConfig();$('next-status').textContent='{{t:next.task_settings_saved_repeat_preview}}'}catch(e){$('task-save-status').textContent=e.message;clearNext();$('next-status').textContent=e.message}});
            function clearPersonal(){personalGeneration++;personalEdit=null;$('personal-result').hidden=true;$('personal-apply').disabled=true}
            async function loadPersonal(task){clearPersonal();personalData=null;$('personal-preview').disabled=true;$('personal-roles').replaceChildren();const generation=personalGeneration;
            if(!task){$('personal-status').textContent='{{t:next.choose_a_task}}';return}$('personal-status').textContent='{{t:next.reading_personal_policy}}';
            try{const response=await fetch('/api/settings?task='+encodeURIComponent(task),{cache:'no-store'}),d=await response.json();if(generation!==personalGeneration)return;if(!response.ok)throw Error(d.message??d.error);personalData={...d,task};
            for(const r of d.roles){const field=document.createElement('fieldset');field.dataset.personalRole=r.role;const legend=document.createElement('legend');legend.textContent=r.role+(r.independent?' {{t:next.independent_vendor_required}}':'');field.append(legend);
            const grid=document.createElement('div');grid.className='next-fields';field.append(grid);const names=document.createElement('input');names.dataset.roleKind='profiles';names.value=r.profiles.join(', ');names.maxLength=2000;labelled(grid,'{{t:next.profiles_in_order_comma_separated}}',names);
            const strategy=document.createElement('select');strategy.dataset.roleKind='strategy';option(strategy,'first','{{t:next.first_available}}');option(strategy,'rotate','{{t:next.rotation}}');strategy.value=r.strategy;labelled(grid,'{{t:next.strategy}}',strategy);
            const failure=document.createElement('select');failure.dataset.roleKind='on_vendor_failure';option(failure,'','{{t:next.inherit_global_policy}}');for(const value of ['stop','confirm','auto'])option(failure,value,value);failure.value=r.on_vendor_failure??'';labelled(grid,'{{t:next.vendor_error_for_role}}',failure);
            const choices=document.createElement('p');choices.className='muted';choices.textContent='{{t:next.available}} '+r.choices.map(p=>p.label).join('; ');field.append(choices);for(const input of [names,strategy,failure])input.addEventListener('input',()=>{clearPersonal();$('personal-status').textContent='{{t:next.policy_changed_repeat_preview}}'});$('personal-roles').append(field)}
            for(const key of personalKeys)$('personal-'+key).value=d[key];$('personal-preview').disabled=false;$('personal-status').textContent='{{t:next.personal_policy_loaded_preview_will}} '+task+'.';
            }catch(e){if(generation===personalGeneration)$('personal-status').textContent=e.message}}
            for(const key of personalKeys)$('personal-'+key).addEventListener('input',()=>{clearPersonal();$('personal-status').textContent='{{t:next.policy_changed_repeat_preview}}'});
            $('personal-reload').addEventListener('click',()=>loadPersonal($('next-task').value));
            function personalChanges(){const changes={roles:Object.create(null)};for(const field of $('personal-roles').querySelectorAll('[data-personal-role]')){const row=Object.create(null);for(const control of field.querySelectorAll('[data-role-kind]'))row[control.dataset.roleKind]=control.dataset.roleKind==='profiles'?control.value.split(',').map(p=>p.trim()).filter(Boolean):control.value||null;changes.roles[field.dataset.personalRole]=row}
            for(const key of personalKeys){const input=$('personal-'+key);if(!input.reportValidity())throw Error('{{t:next.check}} '+key);changes[key]=input.type==='number'?Number(input.value):input.value}return changes}
            $('personal-preview').addEventListener('click',async()=>{if(!personalData)return;clearPersonal();const generation=personalGeneration;$('personal-preview').disabled=true;
            try{const request={task:personalData.task,target:'personal',revision:personalData.revision,changes:personalChanges()},d=await settingsPost('/api/settings/preview',request);if(generation!==personalGeneration)return;
            personalEdit={...request,preview_sha256:d.preview_sha256};$('personal-file').textContent=d.file;$('personal-diff').textContent=d.diff;$('personal-cast').textContent=d.preview.would_run.map(plannedDispatch).join('\\n')+'\\n'+previewText(d.preview);$('personal-problems').textContent=problemText(d.preview);$('personal-apply').disabled=!d.apply_allowed;$('personal-result').hidden=false;$('personal-status').textContent=d.changed?'{{t:next.check_the_personal_policy_diff}}':'{{t:next.no_changes}}';
            }catch(e){if(generation===personalGeneration)$('personal-status').textContent=e.message}finally{if(personalData)$('personal-preview').disabled=false}});
            $('personal-apply').addEventListener('click',async()=>{if(!personalEdit)return;const edit=personalEdit;clearPersonal();personalData=null;$('personal-preview').disabled=true;$('personal-status').textContent='{{t:next.saving_personal_policy}}';
            try{const result=await settingsPost('/api/settings/apply',edit);$('settings-receipt').textContent='{{t:next.personal_policy_saved}} '+result.file+' · backup: '+result.backup;await loadConfig()}catch(e){$('personal-status').textContent=e.message}});
            function metricValue(v){return v==null?'{{t:next.unknown}}':typeof v==='number'?new Intl.NumberFormat('ru',{maximumFractionDigits:4}).format(v):String(v)}
            function metricCoverage(m){return (m?.known_count??0)+' / '+(m?.unknown_count??0)}
            function statsRow(title,m,percentiles=false){return [title,metricValue(m?.total),metricCoverage(m),percentiles?metricValue(m?.p50)+' / '+metricValue(m?.p95):'—']}
            function card(title,value){const div=document.createElement('div');div.className='card';const strong=document.createElement('strong');strong.textContent=metricValue(value);div.append(strong,document.createTextNode(title));$('analytics-cards').append(div)}
            async function loadAnalytics(){const generation=++analyticsGeneration;$('analytics-status').textContent='{{t:next.reading_logs}}';
            try{const q=new URLSearchParams({task:$('analytics-task').value.trim(),run:$('analytics-run').value.trim()}),response=await fetch('/api/analytics?'+q,{cache:'no-store'}),d=await response.json();if(generation!==analyticsGeneration)return;if(!response.ok)throw Error(d.error);
            const m=d.metrics,t=m.telemetry,cost=t.cost_usd;$('analytics-warning').textContent=(d.incomplete?'{{t:next.incomplete_data}} '+(d.skipped||[]).join(', ')+' {{t:next.exact_total_spending_is_unknown}} ':'')+(cost.unknown_count?'{{t:next.unpriced}} '+cost.unknown_count+' {{t:next.attempts}} ':'');
            $('analytics-cards').replaceChildren();card('{{t:next.runs}}',d.outcomes.runs);card('{{t:next.ready_for_human_decision}}',d.outcomes.ready_for_human);card('{{t:next.review_passed}}',d.outcomes.review_passed);card('{{t:next.accepted_by_human}}',d.outcomes.human_accepted);card('{{t:next.vendor_calls}}',m.role_runs.total);card('{{t:next.successful_vendor_calls}}',d.outcomes.vendor_ok);card('{{t:next.attempts_with_refunded_budget}}',m.role_runs.uncharged_attempts);card(d.exact_cost_available?'{{t:next.spending_usd}}':'{{t:next.known_spending_usd}}',cost.total);
            table('analytics-metrics',[statsRow('{{t:next.cost_usd}}',cost),statsRow('Input tokens',t.tokens.input),statsRow('Output tokens',t.tokens.output),statsRow('Total tokens',t.tokens.total),statsRow('{{t:next.call_time_ms}}',t.duration_millis,true),statsRow('{{t:next.human_wait_time_ms}}',m.human.wait_millis,true),['{{t:next.failover_vendor_errors}}',m.iterations.failovers+' / '+m.iterations.vendor_failure_failovers,'—','—'],['{{t:next.observed_rate_limit_retries}}',d.retry.observed_rate_limit_retries,'{{t:next.based_on_pairs_of_attempts}}','—'],['{{t:next.requested_backoff_ms}}',d.retry.planned_backoff_millis,d.retry.planned_pauses+' {{t:next.pauses}}','—'],['{{t:next.quota_gates_config_errors}}',m.failures.quota+' / '+m.failures.machine_gate+' / '+m.failures.configuration,'—','—']]);
            table('analytics-settings',(d.settings||[]).map(s=>[s.vendor,s.model_requested??'{{t:next.unknown_2}}',s.model_reported??'{{t:next.not_reported}}',(s.effort_requested??'{{t:next.unknown_3}}')+' · '+(s.runner??'{{t:next.unknown_3}}'),s.calls+' / '+s.failed_calls,metricValue(s.cost_usd.total)+' · '+metricCoverage(s.cost_usd),metricValue(s.duration_millis.p50)+' / '+metricValue(s.duration_millis.p95)]));
            table('analytics-runs',d.runs.map(r=>{const rm=r.metrics,c=rm.telemetry.cost_usd,f=r.findings,ch=r.chain;return [r.run_id+' / '+(r.task_id??'—'),(r.reason??'—')+' / '+(r.candidate_review_passed===true?'review ok':r.candidate_review_passed===false?'review fail':'review ?')+' / '+(r.decision.decision??r.decision.state??'{{t:next.no_decision}}'),rm.role_runs.total+' · '+metricValue(c.total)+' USD · '+metricCoverage(c),rm.iterations.fix_rounds+' / '+rm.iterations.failovers+' / '+r.retry.planned_pauses,f.observed_ids+' ID · closed '+f.closed_transitions+' · persisted '+f.persisted_transitions,(ch.runs||[]).join(' → ')+(ch.role_runs!=null?' · '+ch.role_runs+' {{t:next.calls}} '+metricValue(ch.cost_usd)+' {{t:next.usd_unpriced}} '+(ch.unpriced_calls??'?')+' · '+(ch.elapsed_known===false?'{{t:next.time_unknown}}':metricValue(ch.elapsed_seconds)+' {{t:next.s}}'):'')]}));
            $('analytics-details').replaceChildren();for(const r of d.runs){const details=document.createElement('details'),summary=document.createElement('summary');summary.textContent=r.run_id+' {{t:next.timeline}}';details.append(summary);for(const s of r.timeline)line(details,(s.stage??s.step??'—')+' · '+(s.profile??'')+' · '+(s.vendor??'')+' · '+(s.code??(s.ok===true?'ok':s.ok===false?'fail':'?'))+(s.reused_from?' · reused '+s.reused_from:''));$('analytics-details').append(details)}
            const callDetails=document.createElement('details'),callSummary=document.createElement('summary');callSummary.textContent='{{t:next.attempts_requested_run_parameters_reported}}';callDetails.append(callSummary);
            for(const c of d.calls||[])line(callDetails,(c.workflow_run_id??'run ?')+' / '+(c.stage??c.role??'—')+' · '+(c.profile??'{{t:next.profile}}')+' · '+(c.code??'{{t:next.code}}')+' · requested '+(c.model_requested??'?')+' / '+(c.effort_requested??'?')+' · launch '+(c.launch_status??'?')+' '+(c.launch_effective?.model??'?')+' / '+(c.launch_effective?.effort??'?')+' · reported '+(c.model_reported??'?')+' · '+metricValue(c.cost_usd)+' USD');$('analytics-details').append(callDetails);
            $('analytics-status').textContent='{{t:next.read}} '+new Date(d.observed_at).toLocaleTimeString()+'{{t:next.known_unknown_number_of_measurements}}';
            }catch(e){if(generation===analyticsGeneration)$('analytics-status').textContent='{{t:next.analytics_unavailable}} '+e.message}}
            for(const id of ['analytics-task','analytics-run'])$(id).addEventListener('input',()=>{analyticsGeneration++;$('analytics-status').textContent='{{t:next.filter_changed_click_refresh_analytics}}'});
            $('analytics-reload').addEventListener('click',loadAnalytics);loadAnalytics();
            """;
}
