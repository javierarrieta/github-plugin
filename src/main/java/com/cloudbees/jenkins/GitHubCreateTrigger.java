package com.cloudbees.jenkins;

import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Set;

public class GitHubCreateTrigger implements GitHubTrigger {

    @DataBoundConstructor
    public GitHubCreateTrigger() {
        super();
    }

    public void onPost() {

    }

    public void onPost(String triggeredByUser) {

    }

    public Set<GitHubRepositoryName> getGitHubRepositories() {
        return null;
    }
}
