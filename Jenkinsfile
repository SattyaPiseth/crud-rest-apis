@Library('shared-library@master') _

pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        skipDefaultCheckout(true)
    }

    environment {
        PROJECT_NAME = 'crud-rest-apis'
        GIT_REPO = 'https://github.com/SattyaPiseth/crud-rest-apis.git'
        GIT_BRANCH = 'main'
    }

    stages {
        stage('Checkout') {
            steps {
                deleteDir()
                git(branch: env.GIT_BRANCH, url: env.GIT_REPO)
                sh 'git rev-parse HEAD'
            }
        }

        stage('Verify JDK Tools') {
            steps {
                script {
                    ['17', '21', '25', '27'].each { version ->
                        def jdkHome = tool(name: "temurin-${version}", type: 'hudson.model.JDK')
                        withEnv(["JAVA_HOME=${jdkHome}", "PATH+JDK=${jdkHome}/bin"]) {
                            sh 'java -version && javac -version'
                        }
                    }
                }
            }
        }

        stage('Verify Gradle Project') {
            steps {
                script {
                    if (!fileExists('gradlew') || !fileExists('build.gradle')) {
                        error('Expected the crud-rest-apis Gradle wrapper and build.gradle.')
                    }
                }
            }
        }

        stage('Test and Coverage') {
            steps {
                script {
                    def jdkHome = tool(name: 'temurin-27', type: 'hudson.model.JDK')
                    withEnv(["JAVA_HOME=${jdkHome}", "PATH+JDK=${jdkHome}/bin"]) {
                        sh '''#!/bin/sh
set -eu
# Existing integration tests require Docker and pull their PostgreSQL image.
sh ./gradlew --no-daemon test jacocoTestReport
# Never scan as if coverage were available when report generation was skipped.
test -s build/reports/jacoco/test/jacocoTestReport.xml
mkdir -p .jenkins-sonar
"$JAVA_HOME/bin/javap" -classpath build/classes/java/main \
    sattya.piseth.crudrestapis.shared.domain.TenantEntity \
    sattya.piseth.crudrestapis.notification.application.IngestionResult \
    > .jenkins-sonar/accessor-signatures.txt
'''
                    }
                }
            }
            post {
                always {
                    // Publish results even if a test fails; Gradle's failure still stops analysis.
                    junit(testResults: 'build/test-results/test/TEST-*.xml', allowEmptyResults: true)
                    archiveArtifacts(
                        artifacts: 'build/reports/jacoco/test/**,build/test-results/test/TEST-*.xml',
                        allowEmptyArchive: true
                    )
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                runSonarScan(
                    projectKey: env.PROJECT_NAME,
                    projectName: env.PROJECT_NAME,
                    jdkToolName: 'temurin-27',
                    scannerToolName: 'sonar-scanner',
                    installationName: 'sonarqube',
                    debug: true,
                    scannerDebug: true,
                    captureWarnings: true,
                    detectTests: true,
                    detectJacoco: true,
                    coveragePaths: 'build/reports/jacoco/test/jacocoTestReport.xml',
                    scannerFinalFieldCompatibility: true,
                    failOnUnresolvedTypes: false,
                    failOnScannerWarnings: false
                )
            }
        }
    }

    post {
        success { echo '[SUCCESS] Tests, coverage generation and Quality Gate passed.' }
        failure { echo '[FAILED] Inspect test results and the SonarQube Quality Gate conditions.' }
        unstable { echo '[UNSTABLE] Jenkins pipeline unstable.' }
        always {
            echo "Pipeline: ${env.JOB_NAME}; Build: ${env.BUILD_NUMBER}; Result: ${currentBuild.currentResult}"
            echo "Build URL: ${env.BUILD_URL}"
            // Fixed metadata only. Do not archive scanner debug logs or environment dumps.
            archiveArtifacts(
                artifacts: '.jenkins-sonar/accessor-signatures.txt,.jenkins-sonar/gradle-classpath.properties,.jenkins-sonar/scanner-warning-counts,sonar-project.properties',
                allowEmptyArchive: true
            )
        }
        cleanup {
            cleanWs(deleteDirs: true, disableDeferredWipeout: true)
        }
    }
}
