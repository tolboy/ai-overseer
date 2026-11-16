package dev.oseer.dashboard;

/** Connection editor. A key field is rendered only when the OS vault probe succeeded. */
public final class ConnectionSettingsUi {
    private ConnectionSettingsUi() {}

    public static String html() { return html(false); }

    public static String html(boolean osStore) {
        String option = osStore ? "<option value=\"os\">{{t:conn.key_in_windows_storage}}</option>" : "";
        String field = osStore
                ? "<label id=\"connection-secret-field\" for=\"connection-secret\" hidden>{{t:conn.key_for_windows_storage}}<input id=\"connection-secret\" name=\"secret\" type=\"password\" maxlength=\"2048\" autocomplete=\"off\" spellcheck=\"false\"><span class=\"muted\">{{t:conn.the_value_is_saved_once}}</span></label>"
                : "";
        String note = osStore
                ? "<p class=\"muted\">{{t:conn.if_windows_storage_is_selected}}</p>"
                : "";
        return HTML.replace("{{OS_STORE_OPTION}}", option)
                .replace("{{OS_STORE_FIELD}}", field)
                .replace("{{OS_STORE_NOTE}}", note);
    }

    private static final String HTML = """
            <section id="connection-editor" aria-labelledby="connection-editor-title" data-token="{{SETTINGS_TOKEN}}">
            <style>
            #connection-editor .connection-toolbar{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
            #connection-editor .connection-actions{display:flex;gap:6px;flex-wrap:nowrap}
            #connection-editor .connection-actions button{white-space:nowrap}
            #connection-editor button:disabled{cursor:wait;opacity:.55}
            #connection-editor form{margin:16px 0;padding:20px;border:1px solid #405363;border-radius:12px;background:#151f28}
            #connection-editor fieldset{border:0;margin:0;padding:0}
            #connection-editor .connection-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}
            #connection-editor label{display:flex;flex-direction:column;gap:6px}
            #connection-editor input,#connection-editor select{box-sizing:border-box;width:100%;min-width:0;background:#19232c;border:1px solid #405363;border-radius:6px;padding:8px 12px;color:inherit;font:inherit}
            #connection-editor .connection-checkbox{display:flex;flex-direction:row;gap:8px;align-items:center;margin:16px 0}
            #connection-editor input[type=checkbox]{width:auto;min-width:0;margin:0}
            #connection-editor input:read-only{color:#9caebd}
            #connection-editor .connection-address{white-space:nowrap}
            #connection-editor .connection-feedback{min-height:1.5em;white-space:pre-wrap;overflow-wrap:anywhere}
            #connection-editor .connection-good{color:#9ee9c6}
            #connection-editor .connection-problems{white-space:pre-wrap}
            #connection-editor [hidden]{display:none!important}
            </style>
            <h2 id="connection-editor-title">{{t:conn.api_keys_and_endpoints}}</h2>
            <p class="muted">{{t:conn.add_an_api_service_or}}</p>
            {{OS_STORE_NOTE}}
            <div class="connection-toolbar"><button id="connection-add" type="button">{{t:conn.add_connection}}</button><button id="connection-reload" type="button">{{t:conn.reload_connections}}</button></div>
            <p id="connection-feedback" class="connection-feedback muted" role="status" aria-live="polite"></p>
            <p id="connection-problems" class="connection-problems bad" hidden></p>
            <div class="scroll" tabindex="0"><table><thead><tr><th>{{t:conn.connection_service}}</th><th>{{t:conn.endpoint}}</th><th>{{t:conn.key_source}}</th><th>{{t:conn.status}}</th><th>{{t:conn.enabled}}</th><th>{{t:conn.actions}}</th></tr></thead><tbody id="connection-editor-rows"></tbody></table><div id="connection-editor-empty" class="empty" hidden>{{t:conn.no_connections_yet_add_a}}</div></div>
            <form id="connection-editor-form" hidden>
            <fieldset id="connection-editor-fields">
            <h3 id="connection-form-title">{{t:conn.new_connection}}</h3>
            <div class="connection-fields">
            <label for="connection-id">{{t:conn.connection_name}}<input id="connection-id" name="id" type="text" required maxlength="64" pattern="[a-z][a-z0-9_\\-]{0,63}" autocomplete="off" spellcheck="false"><span class="muted">{{t:conn.lowercase_latin_letters_digits_hyphens}}</span></label>
            <label for="connection-provider">{{t:conn.service}}<select id="connection-provider" name="provider"><option value="openrouter">OpenRouter</option><option value="gonkarouter">GonkaRouter</option><option value="joingonka">JoinGonka Gateway</option><option value="custom">{{t:conn.compatible_api}}</option></select></label>
            <label for="connection-base-url">{{t:conn.api_endpoint}}<input id="connection-base-url" name="base_url" type="url" required autocomplete="off" spellcheck="false"><span class="muted">{{t:conn.https_for_remote_services_http}}</span></label>
            <label for="connection-auth">{{t:conn.authentication}}<select id="connection-auth" name="auth">{{OS_STORE_OPTION}}<option value="env">{{t:conn.key_from_environment_variable}}</option><option value="none">{{t:conn.no_key_local_api}}</option></select></label>
            <label id="connection-env-field" for="connection-env">{{t:conn.environment_variable_holding_the_key}}<input id="connection-env" name="env" type="text" maxlength="128" pattern="[A-Za-z_][A-Za-z0-9_]{0,127}" autocomplete="off" spellcheck="false"><span class="muted">{{t:conn.for_example_openrouter_api_key}}</span></label>
            {{OS_STORE_FIELD}}
            </div>
            <label class="connection-checkbox" for="connection-enabled"><input id="connection-enabled" name="enabled" type="checkbox" checked>{{t:conn.connection_enabled}}</label>
            <p class="muted">{{t:conn.saving_does_not_assign_the}}</p>
            <div class="connection-toolbar"><button id="connection-save" type="submit">{{t:conn.add}}</button><button id="connection-cancel" type="button">{{t:conn.cancel}}</button></div>
            </fieldset></form>
            <p class="muted">{{t:conn.direct_api_profiles_handle_text}}</p>

            <script>
            (()=>{
            const byId=id=>document.getElementById(id),section=byId('connection-editor'),form=byId('connection-editor-form');
            const processToken=section.dataset.token,fields=byId('connection-editor-fields'),body=byId('connection-editor-rows');
            const idInput=byId('connection-id'),providerInput=byId('connection-provider'),baseInput=byId('connection-base-url'),authInput=byId('connection-auth'),envInput=byId('connection-env'),secretInput=byId('connection-secret'),enabledInput=byId('connection-enabled');
            const defaults={openrouter:{url:'https://openrouter.ai/api/v1',env:'OPENROUTER_API_KEY'},gonkarouter:{url:'https://api.gonkarouter.io/v1',env:'GONKAROUTER_API_KEY'},joingonka:{url:'https://gate.joingonka.ai/v1',env:'JOINGONKA_API_KEY'}};
            let pending=false,editing=null;
            const messages={connection_revision_conflict:'{{t:conn.the_connection_was_changed_in}}',connection_in_use:'{{t:conn.a_profile_uses_this_connection}}',connection_exists:'{{t:conn.a_connection_with_this_name}}',connection_not_found:'{{t:conn.the_connection_can_no_longer}}',connection_layout_unsupported:'{{t:conn.the_file_uses_unsupported_yaml}}',connection_store_unavailable:'{{t:conn.connection_files_are_unavailable_check}}',role_api_key_missing:'{{t:conn.the_key_is_missing_or}}',role_api_auth_invalid:'{{t:conn.the_service_rejected_the_key}}',role_api_connection_disabled:'{{t:conn.the_connection_is_disabled_enable}}',role_api_rate_limited:'{{t:conn.the_service_temporarily_rate_limited}}',role_api_credits_exhausted:'{{t:conn.the_service_reported_insufficient_funds}}',role_api_budget_exhausted:'{{t:conn.the_service_reported_an_exhausted}}',role_api_access_denied:'{{t:conn.the_service_denied_access_check}}',role_api_network_error:'{{t:conn.could_not_reach_the_service}}',role_api_provider_error:'{{t:conn.the_service_returned_an_error}}',role_api_timeout:'{{t:conn.the_service_timed_out_authentication}}',credential_rejected:'{{t:conn.the_key_was_rejected_and}}',credential_store_not_selected:'{{t:conn.this_connection_does_not_use}}',credential_store_unavailable:'{{t:conn.os_storage_is_unavailable_the}}'};
            function feedback(text,error=false){const target=byId('connection-feedback');target.textContent=text;target.className='connection-feedback '+(error?'bad':'connection-good')}
            function setPending(value){pending=value;fields.disabled=value;byId('connection-add').disabled=value;byId('connection-reload').disabled=value;body.querySelectorAll('button').forEach(button=>button.disabled=value)}
            function cell(row,value,className){const target=document.createElement('td');target.textContent=value??'—';if(className)target.className=className;row.append(target);return target}
            function button(label,handler){const target=document.createElement('button');target.type='button';target.textContent=label;target.disabled=pending;target.addEventListener('click',handler);return target}
            function authState(auth,evidence){if(auth?.state==='ready'&&evidence?.state==='confirmed_by_model')return '{{t:conn.key_set_service_accepted_the}} '+evidence.model+(evidence.models_failed?' {{t:conn.other_models_with_errors}} '+evidence.models_failed+')':'');if(auth?.state==='ready'&&evidence?.state==='rejected')return '{{t:conn.key_set_service_rejected_the}} '+evidence.model;switch(auth?.state){case 'not_required':return '{{t:conn.no_key_required}}';case 'ready':return '{{t:conn.key_set_authentication_unchecked}}';case 'invalid':return '{{t:conn.invalid_key_format}}';case 'unavailable':return '{{t:conn.key_source_unavailable}}';case 'disabled':return '{{t:conn.disabled}}';default:return '{{t:conn.key_not_set}}'}}
            function render(rows){body.replaceChildren();for(const connection of rows){const row=document.createElement('tr');cell(row,connection.id+' · '+connection.provider);cell(row,connection.base_url,'connection-address');cell(row,connection.auth?.source??'none');cell(row,authState(connection.auth,connection.auth_evidence));cell(row,connection.enabled?'{{t:conn.yes}}':'{{t:conn.no}}');const actions=cell(row,'');actions.className='connection-actions';actions.append(button('{{t:conn.edit}}',()=>edit(connection)),button('{{t:conn.check_key}}',()=>request('check',{id:connection.id,revision:connection.revision})),button(connection.enabled?'{{t:conn.disable}}':'{{t:conn.enable}}',()=>request(connection.enabled?'disable':'enable',{id:connection.id,revision:connection.revision})),button('{{t:conn.delete}}',()=>{if(!window.confirm('{{t:conn.delete_connection}} '+connection.id+'{{t:conn.the_key_will_not_be}}'))return;request('remove',{id:connection.id,revision:connection.revision})}));body.append(row)}byId('connection-editor-empty').hidden=rows.length>0}
            async function readList(){const response=await fetch('/api/connections',{cache:'no-store',credentials:'same-origin'});let data;try{data=await response.json()}catch(error){throw {status:response.status,code:'connection_store_unavailable'}}if(!response.ok||!Array.isArray(data.connections))throw {status:response.status,code:data.code??'connection_store_unavailable',message:data.message};render(data.connections);const problems=Object.entries(data.problems??{}).map(([name,message])=>name+': '+message);byId('connection-problems').textContent=problems.join(String.fromCharCode(10));byId('connection-problems').hidden=problems.length===0}
            function syncAuth(){const mode=authInput.value;const env=mode==='env';byId('connection-env-field').hidden=!env;envInput.disabled=!env;envInput.required=env;const secretField=byId('connection-secret-field');if(secretField){secretField.hidden=mode!=='os';if(secretInput){secretInput.disabled=mode!=='os';if(mode!=='os')secretInput.value=''}}}
            function syncProvider(fill){const preset=defaults[providerInput.value];const leavingPreset=Object.values(defaults).some(value=>value.url===baseInput.value);baseInput.readOnly=Boolean(preset);authInput.querySelector('option[value=none]').disabled=Boolean(preset);if(preset){if(authInput.value==='none'||(fill&&authInput.value!=='os'))authInput.value='env';if(fill){baseInput.value=preset.url;if(!envInput.value||Object.values(defaults).some(value=>value.env===envInput.value))envInput.value=preset.env}}else if(fill&&leavingPreset)baseInput.value='';syncAuth()}
            function closeForm(){if(secretInput)secretInput.value='';form.hidden=true;editing=null;idInput.readOnly=false}
            function begin(){if(pending)return;editing=null;form.reset();idInput.readOnly=false;providerInput.value='openrouter';envInput.value='OPENROUTER_API_KEY';syncProvider(true);byId('connection-form-title').textContent='{{t:conn.new_connection}}';byId('connection-save').textContent='{{t:conn.add}}';form.hidden=false;feedback('{{t:conn.choose_a_service_and_key}}');idInput.focus()}
            function edit(connection){if(pending)return;const source=connection.auth?.source??'none';if(source.startsWith('os-store:')&&!authInput.querySelector('option[value=os]')){feedback('{{t:conn.os_storage_is_currently_unavailable}}',true);return}const same=editing&&editing.id===connection.id;editing={id:connection.id,revision:connection.revision,credentialRef:source};if(!same){idInput.value=connection.id;idInput.readOnly=true;providerInput.value=connection.provider;baseInput.value=connection.base_url;authInput.value=source.startsWith('env:')?'env':source.startsWith('os-store:')?'os':'none';envInput.value=source.startsWith('env:')?source.slice(4):'';if(secretInput)secretInput.value='';enabledInput.checked=Boolean(connection.enabled)}else idInput.readOnly=true;syncProvider(false);byId('connection-form-title').textContent='{{t:conn.edit_connection}} '+connection.id;byId('connection-save').textContent='{{t:conn.save}}';form.hidden=false;feedback(same?'{{t:conn.your_draft_is_preserved_saving}}':'{{t:conn.changes_will_be_saved_only}}');if(!same)baseInput.focus()}
            function errorText(error){if(messages[error?.code])return messages[error.code];if(error?.code?.startsWith('role_api_'))return '{{t:conn.the_service_did_not_confirm}}';if(error?.code==='connection_invalid')return typeof error.message==='string'?error.message:'{{t:conn.check_the_name_api_endpoint}}';if(error?.status===409)return '{{t:conn.settings_changed_or_the_operation}}';if(typeof error?.status==='number')return typeof error.message==='string'&&error.message?error.message:'{{t:conn.the_dashboard_did_not_complete}}';return '{{t:conn.cannot_reach_the_dashboard_changes}}'}
            function checkResult(data){if(data.auth_verified===true)return '{{t:conn.authentication_confirmed_by_the_service}}';if(data.code==='no_auth_required')return '{{t:conn.no_key_is_required_for}}';return '{{t:conn.the_key_source_is_set}}'}
            async function request(operation,payload,fromForm=false){if(pending)return null;setPending(true);feedback(operation==='check'?'{{t:conn.checking_connection_without_calling_a}}':'{{t:conn.saving_change}}');try{const response=await fetch('/api/connections/'+operation,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-Overseer-Token':processToken},body:JSON.stringify(payload)});let data;try{data=await response.json()}catch(error){throw {status:response.status}}if(!response.ok||data.ok!==true)throw {status:response.status,code:data.code,message:data.message};if(operation==='check'){feedback(checkResult(data));return data}if(operation==='auth-set'){const stored='{{t:conn.key_saved_in_windows_storage}}';feedback(stored);try{await readList()}catch(error){feedback(stored+' {{t:conn.the_list_did_not_refresh}}',true)}return data}if(operation==='auth-remove'){feedback('{{t:conn.local_storage_entry_deleted_the}}');try{await readList()}catch(error){feedback('{{t:conn.local_storage_entry_deleted_the_2}}',true)}return data}if(fromForm||(operation==='remove'&&editing?.id===payload.id))closeForm();document.dispatchEvent(new CustomEvent('oseer:connections-changed'));const saved=operation==='remove'?'{{t:conn.connection_deleted_the_key_was}}':'{{t:conn.connection_saved_role_assignments_were}}';feedback(saved);try{await readList()}catch(error){feedback(saved+' {{t:conn.the_list_did_not_refresh}}',true)}return data}catch(error){feedback(errorText(error),true);return null}finally{setPending(false)}}
            form.addEventListener('submit',async event=>{event.preventDefault();if(pending||!form.reportValidity())return;const mode=authInput.value;const id=idInput.value.trim();let secret='';if(secretInput&&mode==='os'){secret=secretInput.value;secretInput.value=''}const payload={id,provider:providerInput.value,base_url:baseInput.value.trim(),credential_ref:mode==='none'?null:mode==='os'?'os-store:oseer/'+id:'env:'+envInput.value.trim(),enabled:enabledInput.checked};if(editing){payload.id=editing.id;payload.revision=editing.revision;if(mode==='os')payload.credential_ref=editing.credentialRef?.startsWith('os-store:')?editing.credentialRef:'os-store:oseer/'+payload.id}const saved=await request(editing?'update':'add',payload,true);if(saved&&secret){const revision=saved.connection&&saved.connection.revision;if(typeof revision!=='string'||!await request('auth-set',{id:payload.id,revision,secret},false))feedback('{{t:conn.reference_saved_but_the_key}}',true)}secret=''});
            byId('connection-add').addEventListener('click',begin);byId('connection-cancel').addEventListener('click',()=>{closeForm();feedback('{{t:conn.editing_canceled}}')});providerInput.addEventListener('change',()=>syncProvider(true));authInput.addEventListener('change',syncAuth);
            byId('connection-reload').addEventListener('click',async()=>{if(pending)return;setPending(true);try{await readList();feedback(editing?'{{t:conn.list_refreshed_your_draft_is}}':'{{t:conn.connections_reloaded}}')}catch(error){feedback(errorText(error),true)}finally{setPending(false)}});
            setPending(true);readList().then(()=>feedback('{{t:conn.connections_loaded}}')).catch(error=>feedback(errorText(error),true)).finally(()=>setPending(false));
            })();
            </script></section>
            """;
}
