package dev.oseer.config;

import dev.oseer.execution.ModelCheck;
import dev.oseer.json.Json;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/** Saves an ordered role composition while keeping launch/tool contracts in internal templates. */
public final class RoleRosterEditor {
    public record Outcome(int status, Map<String,Object> report) {}
    private final Path home;
    public RoleRosterEditor(Path home) { this.home=home.toAbsolutePath().normalize(); }

    /** Explicit user action. Never infers or grants admission; callers may verify pending models afterward. */
    public Outcome apply(Map<String,Object> payload) {
        try {
            Files.createDirectories(home);
            try (var channel=FileChannel.open(home.resolve(".model-settings.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                 var lock=channel.tryLock()) {
                if(lock==null)return refused(409,"settings_busy");
                return "fallbacks".equals(payload==null?null:payload.get("action"))?saveFallbacks(payload):save(payload);
            }
        } catch(NotIndependent shared) {
            return new Outcome(400,Map.of("ok",false,"code","reviewer_not_independent","role",shared.role,"writer_vendor",shared.vendor));
        } catch(Changed conflict) { return refused(409,"settings_changed_reload"); }
        catch(Unbound unavailable) { return refused(400,"cli_model_binding_required"); }
        catch(dev.oseer.execution.EffortCapabilities.Unsupported unsupported) { return refused(400,"effort_unsupported"); }
        catch(Invalid | IllegalArgumentException | ConfigException invalid) { return refused(400,"settings_invalid"); }
        catch(java.nio.channels.OverlappingFileLockException busy) { return refused(409,"settings_busy"); }
        catch(Exception unavailable) { return refused(503,"settings_unavailable"); }
    }

    private Outcome save(Map<String,Object> payload)throws Exception {
        keys(payload,Set.of("revision","role","strategy","slots"));
        Path policyFile=home.resolve("policy.yaml");expect(policyFile,text(payload,"revision"));
        var user=UserConfig.load(home);String role=text(payload,"role"),strategy=text(payload,"strategy");
        var capabilities=new dev.oseer.execution.EffortCapabilities(home,user);
        if(user.policy()==null || !user.policy().roles().containsKey(role) || !Set.of("first","rotate").contains(strategy))throw new Invalid();
        if(!(payload.get("slots") instanceof List<?> slots) || slots.isEmpty() || slots.size()>64)throw new Invalid();
        var names=new ArrayList<String>();var pending=new ArrayList<String>();
        var effective=new LinkedHashMap<String,Profile>();
        var updates=new LinkedHashMap<Path,String>();
        var oldBytes=new LinkedHashMap<Path,byte[]>();
        var expected=new LinkedHashMap<Path,String>();expected.put(policyFile,text(payload,"revision"));
        for(Object raw:slots) {
            if(!(raw instanceof Map<?,?> rawMap))throw new Invalid();
            var slot=new LinkedHashMap<String,Object>();
            for(var entry:rawMap.entrySet()) { if(!(entry.getKey() instanceof String key))throw new Invalid();slot.put(key,entry.getValue()); }
            keys(slot,Set.of("profile","revision","model","effort","model_vendor"));
            String name=text(slot,"profile");
            if(!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))throw new Invalid();
            if(names.contains(name))throw new Invalid();names.add(name);
            Profile old=user.profiles().get(name);
            if(old==null || !role.equals(old.role()))throw new Invalid();
            Path file=home.resolve("profiles").resolve(name+".yaml");
            String revision=text(slot,"revision");expect(file,revision);expected.put(file,revision);
            String model=text(slot,"model");
            if(!model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,255}"))throw new Invalid();
            String yaml=Files.readString(file),original=yaml;
            boolean modelChanged=!Objects.equals(old.model(),model);
            String probe=old.verificationProbe();
            if(modelChanged) {
                if(old.connectionRef()==null) {
                    boolean launchBound="orca".equals(old.runner()) || old.args().stream().anyMatch(a->a.contains("{{model}}"));
                    if(!launchBound || probe==null)throw new Unbound();
                    if(!probe.contains("{{model}}")) {
                        if(old.model()==null || !probe.contains(old.model()))throw new Unbound();
                        probe=probe.replace(old.model(),"{{model}}");
                    }
                }
                yaml=replace(yaml,List.of("model"),Json.write(model));
            }
            if(slot.containsKey("model_vendor")) {
                String vendor=text(slot,"model_vendor");
                if(!vendor.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"))throw new Invalid();
                if(old.connectionRef()==null && !Objects.equals(Profile.canonicalVendor(vendor),Profile.canonicalVendor(old.modelVendor())))throw new Invalid();
                if(old.connectionRef()!=null && !Objects.equals(Profile.canonicalVendor(vendor),Profile.canonicalVendor(old.modelVendor())))
                    yaml=replace(yaml,List.of("model_vendor"),Json.write(vendor));
            }
            if(slot.containsKey("effort")) {
                String effort=text(slot,"effort");
                if(!effort.matches("[a-z][a-z0-9_-]{0,63}"))throw new Invalid();
                if(!Objects.equals(effort,old.effort())) {
                    boolean nativeApi=old.connectionRef()!=null;
                    boolean launchBound=nativeApi || "orca".equals(old.runner()) || old.args().stream().anyMatch(a->a.contains("{{effort}}"));
                    if(!launchBound || (!nativeApi && probe==null))throw new Unbound();
                    if(!nativeApi && !probe.contains("{{effort}}")) {
                        if(old.effort()==null || !probe.contains(old.effort()))throw new Unbound();
                        probe=probe.replace(old.effort(),"{{effort}}");
                    }
                    yaml=replace(yaml,List.of("effort"),Json.write(effort));
                }
            }
            String finalEffort=slot.containsKey("effort")?text(slot,"effort"):old.effort();
            boolean effortChanged=!Objects.equals(finalEffort,old.effort());
            if(effortChanged || modelChanged)capabilities.require(old,model,finalEffort,effortChanged);
            if(!Objects.equals(probe,old.verificationProbe()))yaml=replace(yaml,List.of("verification","probe"),Json.write(probe));
            boolean edited=!yaml.equals(original);
            if(edited) {
                if(yaml.lines().anyMatch(line->line.startsWith("verification:"))) {
                    yaml=replace(yaml,List.of("verification","verified_on"),null);
                    yaml=replace(yaml,List.of("verification","connection_fingerprint"),null);
                } else yaml+="\nverification:\n  status: unverified\n";
                var next=Profile.parse(yaml,file.toString());
                if(next.connectionRef()!=null)next=next.withConnection(user.connections().get(next.connectionRef()),home);
                if(next.verified())throw new Invalid();
                updates.put(file,yaml);
            }
            effective.put(name,edited?Profile.parse(yaml,file.toString()):old);
            if(edited || !old.verified())pending.add(name);
        }
        requireIndependence(user,role,names,effective);
        String policy=Files.readString(policyFile);
        policy=replace(policy,List.of("roles",role,"profiles"),Json.write(names));
        policy=replace(policy,List.of("roles",role,"strategy"),Json.write(strategy));
        // A fallback the roster no longer lists cannot stay a fallback; the rest keep their order.
        var spec=user.policy().roles().get(role);
        if(!spec.apiFallbackProfiles().isEmpty()) {
            var kept=spec.apiFallbackProfiles().stream().filter(names::contains).toList();
            if(!kept.equals(spec.apiFallbackProfiles())) {
                policy=replace(policy,List.of("roles",role,"api_fallback_profiles"),kept.isEmpty()?null:Json.write(kept));
                if(kept.isEmpty() || names.stream().allMatch(kept::contains))policy=replace(policy,List.of("roles",role,"fallback_standby"),null);
            }
        }
        Policy.parse(policy,policyFile.toString());updates.put(policyFile,policy);
        // Recheck every revision immediately before the first write. No partial validation edits.
        for(var entry:expected.entrySet())expect(entry.getKey(),entry.getValue());
        for(Path file:updates.keySet())oldBytes.put(file,Files.readAllBytes(file));
        var written=new ArrayList<Path>();
        try {
            for(var entry:updates.entrySet()){ModelCheck.atomicWrite(entry.getKey(),entry.getValue());written.add(entry.getKey());}
        } catch(Exception failure) {
            Collections.reverse(written);
            for(Path file:written)try{ModelCheck.atomicWrite(file,new String(oldBytes.get(file),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
        var revisions=new LinkedHashMap<String,String>();for(String name:names)revisions.put(name,ModelSettingsService.revision(home.resolve("profiles").resolve(name+".yaml")));
        return new Outcome(200,Map.of("ok",true,"code","role_composition_saved","role",role,"pending_verification",pending,
                "profiles",names,"revisions",revisions,"policy_revision",ModelSettingsService.revision(policyFile)));
    }
    /**
     * The fallbacks of one role and what a vendor failure does there, saved on their own so a
     * roster draft in another card is never written with them. A fallback the role does not
     * list yet is appended to its profiles; every fallback is held in standby
     * ({@code fallback_standby: true}), so it runs only when a call fails. A judging role that
     * must be independent refuses a fallback from the writer's vendor: it could never run.
     * Admission is unchanged: an unverified fallback is saved and reported, and the run's
     * resolver still checks verification, independence and budget before a switch.
     */
    private Outcome saveFallbacks(Map<String,Object> payload)throws Exception {
        keys(payload,Set.of("action","revision","role","fallbacks","on_vendor_failure"));
        Path policyFile=home.resolve("policy.yaml");expect(policyFile,text(payload,"revision"));
        var user=UserConfig.load(home);String role=text(payload,"role");
        if(user.policy()==null || !user.policy().roles().containsKey(role))throw new Invalid();
        String mode=text(payload,"on_vendor_failure");
        if(!Set.of("inherit","auto","confirm","stop").contains(mode))throw new Invalid();
        if(!(payload.get("fallbacks") instanceof List<?> raw) || raw.size()>4)throw new Invalid();
        var fallbacks=new ArrayList<String>();
        for(Object item:raw){
            if(!(item instanceof String name) || fallbacks.contains(name))throw new Invalid();
            Profile profile=user.profiles().get(name);
            if(profile==null || !role.equals(profile.role()))throw new Invalid();
            fallbacks.add(name);
        }
        var spec=user.policy().roles().get(role);
        var profiles=new ArrayList<String>(spec.profiles());
        // A standby fallback the operator drops leaves the roster too; otherwise it would
        // silently join the rotation as a primary once fallback_standby no longer hides it.
        if(spec.fallbackStandby())
            for(String old:spec.apiFallbackProfiles())if(!fallbacks.contains(old))profiles.remove(old);
        for(String name:fallbacks)if(!profiles.contains(name))profiles.add(name);
        if(!fallbacks.isEmpty() && profiles.stream().allMatch(fallbacks::contains))
            return refused(400,"fallback_needs_primary");
        if(spec.requireIndependentVendor() && !"implementer".equals(role)) {
            var writers=user.policy().roles().get("implementer");
            Profile writer=writers==null || writers.profiles().isEmpty()?null:user.profiles().get(writers.profiles().get(0));
            String writerVendor=writer==null?null:writer.independenceVendor();
            for(String name:fallbacks)
                if(writerVendor!=null && writerVendor.equals(user.profiles().get(name).independenceVendor()))
                    return new Outcome(400,Map.of("ok",false,"code","fallback_not_independent","role",role,"profile",name,"writer_vendor",writerVendor));
        }
        String policy=Files.readString(policyFile);
        policy=replace(policy,List.of("roles",role,"profiles"),Json.write(profiles));
        policy=replace(policy,List.of("roles",role,"api_fallback_profiles"),fallbacks.isEmpty()?null:Json.write(fallbacks));
        policy=replace(policy,List.of("roles",role,"fallback_standby"),fallbacks.isEmpty()?null:"true");
        policy=replace(policy,List.of("roles",role,"on_vendor_failure"),"inherit".equals(mode)?null:Json.write(mode));
        Policy.parse(policy,policyFile.toString());
        expect(policyFile,text(payload,"revision"));
        ModelCheck.atomicWrite(policyFile,policy);
        var pending=fallbacks.stream().filter(name->!user.profiles().get(name).verified()).toList();
        return new Outcome(200,Map.of("ok",true,"code","fallbacks_saved","role",role,"fallbacks",fallbacks,
                "profiles",profiles,"pending_verification",pending,"policy_revision",ModelSettingsService.revision(policyFile)));
    }

    /**
     * A judging role that must be independent of the writer keeps at least one candidate from
     * another vendor than the implementer the next run starts with. The resolver refuses a
     * same-vendor reader at run time anyway; refusing the save says so where the choice is
     * made instead of as `independent_review_unavailable` after the writer was paid.
     * Only the roles this save changes are judged, so an unrelated edit is never blocked by
     * an existing configuration.
     */
    static void requireIndependence(UserConfig user,String role,List<String> names,Map<String,Profile> edited){
        var roles=new LinkedHashMap<String,List<String>>();
        for(var entry:user.policy().roles().entrySet())roles.put(entry.getKey(),entry.getValue().profiles());
        roles.put(role,names);
        var profiles=new LinkedHashMap<String,Profile>(user.profiles());profiles.putAll(edited);
        List<String> writers=roles.getOrDefault("implementer",List.of());
        if(writers.isEmpty() || profiles.get(writers.get(0))==null)return;
        String writer=profiles.get(writers.get(0)).independenceVendor();
        if(writer==null)return;
        for(var spec:user.policy().roles().values()){
            if(spec.role().equals("implementer") || !spec.requireIndependentVendor())continue;
            if(!role.equals("implementer") && !role.equals(spec.role()))continue;
            List<String> readers=roles.getOrDefault(spec.role(),List.of());
            if(readers.isEmpty())continue;
            boolean independent=readers.stream().map(profiles::get).anyMatch(p->p!=null && !writer.equals(p.independenceVendor()));
            if(!independent)throw new NotIndependent(spec.role(),writer);
        }
    }
    private static String replace(String yaml,List<String> path,String value){return ModelSettingsService.replaceValue(yaml,path,value);}
    private static void keys(Map<String,Object> map,Set<String> allowed){if(map==null || !allowed.containsAll(map.keySet()))throw new Invalid();}
    private static String text(Map<String,Object> map,String key){if(!(map.get(key) instanceof String value) || value.isBlank() || value.length()>512 || value.chars().anyMatch(c->c<32))throw new Invalid();return value;}
    private static void expect(Path file,String revision)throws Exception{if(!ModelSettingsService.revision(file).equals(revision))throw new Changed();}
    private static Outcome refused(int status,String code){return new Outcome(status,Map.of("ok",false,"code",code));}
    static final class NotIndependent extends RuntimeException {
        final String role,vendor;
        NotIndependent(String role,String vendor){super(null,null,false,false);this.role=role;this.vendor=vendor;}
    }
    private static final class Invalid extends RuntimeException {}
    private static final class Changed extends RuntimeException {}
    private static final class Unbound extends RuntimeException {}
}
