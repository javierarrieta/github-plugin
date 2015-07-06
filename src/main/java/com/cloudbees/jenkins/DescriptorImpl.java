package com.cloudbees.jenkins;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import hudson.util.SequentialExecutionQueue;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.codec.binary.Base64;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.inject.Inject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by jarrieta on 12/06/15.
 */
@Extension
public class DescriptorImpl extends TriggerDescriptor {
    private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());
    private transient final SequentialExecutionQueue queue = new SequentialExecutionQueue(Hudson.MasterComputer.threadPoolForRemoting);

    private boolean manageHook;
    private String hookUrl;
    private volatile List<Credential> credentials = new ArrayList<Credential>();

    @Inject
    private transient InstanceIdentity identity;

    public DescriptorImpl() {
        load();
    }

    @Override
    public boolean isApplicable(Item item) {
        return item instanceof AbstractProject;
    }

    @Override
    public String getDisplayName() {
        return "Build when a change is pushed to GitHub";
    }

    /**
     * True if Jenkins should auto-manage hooks.
     */
    public boolean isManageHook() {
        return manageHook;
    }

    public void setManageHook(boolean v) {
        manageHook = v;
        save();
    }

    /**
     * Returns the URL that GitHub should post.
     */
    public URL getHookUrl() throws MalformedURLException {
        return hookUrl != null ? new URL(hookUrl) : new URL(Hudson.getInstance().getRootUrl() + GitHubWebHook.get().getUrlName() + '/');
    }

    public boolean hasOverrideURL() {
        return hookUrl != null;
    }

    public List<Credential> getCredentials() {
        return credentials;
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        JSONObject hookMode = json.getJSONObject("hookMode");
        manageHook = "auto".equals(hookMode.getString("value"));
        if (hookMode.optBoolean("hasHookUrl")) {
            hookUrl = hookMode.optString("hookUrl");
        } else {
            hookUrl = null;
        }
        credentials = req.bindJSONToList(Credential.class, hookMode.get("credentials"));
        save();
        return true;
    }

    public FormValidation doCheckHookUrl(@QueryParameter String value) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(value).openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty(GitHubWebHook.URL_VALIDATION_HEADER, "true");
            con.connect();
            if (con.getResponseCode() != 200) {
                return FormValidation.error("Got " + con.getResponseCode() + " from " + value);
            }
            String v = con.getHeaderField(GitHubWebHook.X_INSTANCE_IDENTITY);
            if (v == null) {
                // people might be running clever apps that's not Jenkins, and that's OK
                return FormValidation.warning("It doesn't look like " + value + " is talking to any Jenkins. Are you running your own app?");
            }
            RSAPublicKey key = identity.getPublic();
            String expected = new String(Base64.encodeBase64(key.getEncoded()));
            if (!expected.equals(v)) {
                // if it responds but with a different ID, that's more likely wrong than correct
                return FormValidation.error(value + " is connecting to different Jenkins instances");
            }

            return FormValidation.ok();
        } catch (IOException e) {
            return FormValidation.error(e, "Failed to test a connection to " + value);
        }

    }

    public FormValidation doReRegister() {
        if (!manageHook) {
            return FormValidation.error("Works only when Jenkins manages hooks");
        }

        int triggered = 0;
        for (AbstractProject<?, ?> job : getJenkinsInstance().getAllItems(AbstractProject.class)) {
            if (!job.isBuildable()) {
                continue;
            }

            GitHubPushTrigger trigger = job.getTrigger(GitHubPushTrigger.class);
            if (trigger != null) {
                LOGGER.log(Level.FINE, "Calling registerHooks() for {0}", job.getFullName());
                trigger.registerHooks();
                triggered++;
            }
        }

        LOGGER.log(Level.INFO, "Called registerHooks() for {0} jobs", triggered);
        return FormValidation.ok("Called re-register hooks for " + triggered + " jobs");
    }

    public static final Jenkins getJenkinsInstance() throws IllegalStateException {
        Jenkins instance = Jenkins.getInstance();
        if (instance == null) {
            throw new IllegalStateException("Jenkins has not been started, or was already shut down");
        }
        return instance;
    }

    public static DescriptorImpl get() {
        return Trigger.all().get(DescriptorImpl.class);
    }

    public static boolean allowsHookUrlOverride() {
        return GitHubPushTrigger.ALLOW_HOOKURL_OVERRIDE;
    }
}
