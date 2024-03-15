mvnHome = tool 'M3'

mvnRepo = "${env.JOB_MAVEN_DIR}"

mvnRepoArg = "-Dmaven.repo.local=\"${mvnRepo}\""

mvn = "${mvnHome}/bin/mvn ${mvnRepoArg}"

git = "/bin/git"

conduit = "~/.conduit/bin/conduit"


def checkout(gitTarget, gitUrl) {

    if (!gitTarget?.trim()) {
        error("Aborting checkout. gitTarget should be set to a branch, revision or tag. It is null or empty")
    }
    if (!gitUrl?.trim()) {
        error("Aborting checkout. gitUrl should be set to a git url. It is null or empty")
    }
    println "NOTE: Removing special chars from gitTarget string"
    gitTarget = gitTarget.replaceAll("[^-a-zA-Z0-9:._/*]", "") // Fix for JENKINS-28022
    stage('Checkout') {
        //**************************************************************
        git_vars = checkout([$class                           : 'GitSCM',
                             branches                         : [[name: gitTarget]],
                             doGenerateSubmoduleConfigurations: false,
                             extensions                       : [],
                             submoduleCfg                     : [],
                             userRemoteConfigs                : [[credentialsId: 'jenkins2',
                                                                  url          : gitUrl]]])

        print git_vars

        buildTimeStamp = sh(returnStdout: true, script: "date -u '+%Y%m%dT%H%M%S'").trim()
        commitShortId = git_vars.GIT_COMMIT.substring(0,7)

    }
}

def checkoutDeploy(gitTarget, gitUrl) {

    if (!gitTarget?.trim()) {
        error("Aborting checkout. gitTarget should be set to a branch, revision or tag. It is null or empty")
    }
    if (!gitUrl?.trim()) {
        error("Aborting checkout. gitUrl should be set to a git url. It is null or empty")
    }
    println "NOTE: Removing special chars from gitTarget string"
    gitTarget = gitTarget.replaceAll("[^-a-zA-Z0-9:._/*]", "") // Fix for JENKINS-28022
    stage('Checkout') {
        //**************************************************************
        git_vars = checkout([$class                           : 'GitSCM',
                             branches                         : [[name: gitTarget]],
                             doGenerateSubmoduleConfigurations: false,
                             extensions                       : [],
                             submoduleCfg                     : [],
                             userRemoteConfigs                : [[credentialsId: 'jenkins2',
                                                                  url          : gitUrl]]])

        print git_vars

        buildTimeStamp = sh(returnStdout: true, script: "date -u '+%Y%m%dT%H%M%S'").trim()
        commitShortId = git_vars.GIT_COMMIT.substring(0,7)

        if (gitTarget.contains('pre-release')) {
            // get 2nd last commit message which should contain the latest version
            buildVersion = sh(
                    script: "${git} log -n 1 --grep='version ' --author=jenkins --pretty=format:%B | cut -d ' ' -f2-",
                    returnStdout: true
            ).trim()
            println "pre-release branch specified, deploying latest version ${buildVersion}"
        }else{
            // set the buildVersion to the tag name which should be a version
            // needs some sanity checking
            buildVersion = gitTarget
            println "Attempting to deploy a version named ${buildVersion}"
        }
    }
}

def checkoutBranch(targetRemote){
    stage("Checkout Build Branch"){
        sh """\
                # --prune cleans up local to match remote
                ${git} fetch ${targetRemote} --prune
                
                # Create a temporary build branch
                ${git} branch --list --quiet build | grep -q "build" && git branch -D build
                ${git} checkout -b build
                """
    }
}

def pushToPreRelease(remoteBranch, targetRemote){
    stage("Push to Pre-Release Branch"){
        sh """\
                export mainVersionTimeStampCommitId=${mainVersion}-${buildTimeStamp}.${commitShortId}

                # Add any changed files to the temporary build branch                
                ${git} add -A
                
                # allow-empty allows a commit even if no changes have occurred locally so that following trigger events can be run
                ${git} commit --allow-empty -m "version \${mainVersionTimeStampCommitId}"
                
                # Checkout pre-release branch locally or create new pre-release branch
                ${git} branch --list --quiet ${remoteBranch} | grep -q "${remoteBranch}" && ( 
                    ${git} checkout ${remoteBranch}
                ) || (
                    ${git} checkout -b ${remoteBranch}
                )
                
                # reset the local pre-release (current) branch to be the same as the current locally commited changes on build branch
                # the trailing -- means removes ambiguity in case of a directory or file called build in the root of the repo
                ${git} reset --hard build --
                ${git} merge -s ours ${targetRemote}/${remoteBranch}
                
                # Cleanup, delete the temporary build branch
                ${git} branch -D build

                # Add our tag which can be used in the deploy pipeline
                ${git} tag "\${mainVersionTimeStampCommitId}"
                ${git} push ${targetRemote} ${remoteBranch}
                ${git} push ${targetRemote} "\${mainVersionTimeStampCommitId}"
                
                """
    }
}

def pushToBranchAndTag(remoteBranch, targetRemote, gitCommitMessage, gitTagName){
    stage("Push to ${remoteBranch} Branch"){
        sh """\

                # Add any changed files to the temporary build branch                
                ${git} add -A
                
                # allow-empty allows a commit even if no changes have occurred locally so that following trigger events can be run
                ${git} commit --allow-empty -m "version ${gitCommitMessage}"
                
                # Checkout pre-release branch locally or create new pre-release branch
                ${git} branch --list --quiet ${remoteBranch} | grep -q "${remoteBranch}" && ( 
                    ${git} checkout ${remoteBranch}
                ) || (
                    ${git} checkout -b ${remoteBranch}
                )
                
                # reset the local pre-release (current) branch to be the same as the current locally commited changes on build branch
                # the trailing -- means removes ambiguity in case of a directory or file called build in the root of the repo
                ${git} reset --hard build --
                ${git} merge -s ours ${targetRemote}/${remoteBranch}
                
                # Cleanup, delete the temporary build branch
                ${git} branch -D build

                # Add our tag which can be used in the deploy pipeline
                ${git} tag ${gitTagName}
                ${git} push ${targetRemote} ${remoteBranch}
                ${git} push ${targetRemote} ${gitTagName}
                
                """
    }
}

def checkoutFabric8DeployBranch(buildVersion) {
    stage("checkout versioned poms for fabric8 deploy"){
        if ( gitTarget.contains('pre-release')){
            ${git} fetch --all
            fabric8DeployBranch = sh(
                    script: "${git} log --all --grep '${buildVersion} --pretty=format:%H",
                    returnStdout: true
            ).trim()
            ${git} checkout -b ${fabric8DeployBranch}
            ${git} pull
        }
        else {
            println ("I don't think this is the correct branch")
        }
    }
}

return this