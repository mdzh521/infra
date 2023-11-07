//git仓库地址
def gitRepo = "http://172.22.203.145:8100/root/infra.git"

//项目名称
def project = "go-biz-finace-center"

// 镜像完整名称
def imageName = "${utils.internalRegistryHost}/go-biz/${project}"

// 外网镜像地址

// 环境列表
def sites = [
    'dev-public': "开发环境(dev-public, 仅需要在下面选择发布stable稳定版)"
    'test-public': "测试环境(test-public, 仅需要在下面选择发布stable稳定版)"
]

def canary = [
    'stable': "稳定版(当前使用版本)"
    'canary': "灰度版本"
]

def canaryLabels = canary.values().join(',')
def canaryOptions = canary.keySet().join(',')
def siteCount = sites.size()
//环境选项
def siteLabels = sites.values().join(',')
//环境选项标签
def siteOptions = sites.keySet().join(',')
//镜像目录
String imageDir = "saas/go-biz"

//流水线内容
pipeline{
    agent{
        kubernetes {
            defaultContainer 'jnlp'
            yaml """
apiVersion: v1
kind: pod
metadata:
labels:
  component: ci
spec:
  containers:
  - name: docker
    image: ${utils.internalRegistryHost}/public/docker:23.0.1-dind-alpine3.17
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
  imagePullSecrets:
  - name: sg-docker-repo
  affinity:
    nodeAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 1
          preference:
            matchExpressions:
              - key: usrd-for
                operator: In
                values:
                  - go-biz
"""
        }
    }
    options{
        skipStagesAfterUnstable()
        buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '',daysToKeepStr: '', numTokeepStr: '30')
    }
    parameters{
        extendeChoice(
            name: '环境',
            defaultValue: 'test-public',
            descriptionPropertyValue: siteLabels,
            multiSelectDelimiter: ',',
            type: 'PT_RADIO',
            value: siteOptions,
            visibleItemCount: siteCount,
        )
        extendeChoice(
            name: '金丝雀版本',
            defaultValue: 'stable',
            descriptionPropertyValue: siteLabels,
            multiSelectDelimiter: ',',
            type: 'PT_RADIO',
            value: siteOptions,
            visibleItemCount: siteCount,
        )
        gitParameter name: 'BRANCH',type: 'PT_BRANCH_TAG', defaultValue: 'release/test', branchFilter: 'origin/(.*)', useRepository: gitRepo,quickFilterEnabled: true
        booleanParam defaultValue: false, description: '打包生产环境docker镜像', name: 'Push_prod_docker_img'
        booleanParam defaultValue: true, description: '部署到k8s集群', name: 'Deploy_dev_K8s'
    }
    stages {
        stage("拉取代码"){
            steps {
                script {
                    utils.gitCheckout(
                        repuUrl: gitRepo,
                        branch: params.BRANCH,
                    )

                }
            }
        }
        // 当推送到外网时，需检查镜像是否存在
        stage("检查镜像是否已存在"){
            when {
                expression { return params.环境 && params.BRANCH && params.Push_prod_docker_img}
            }
            steps{
                container('docker') {
                    script {
                        // 检查镜像是否存在
                        def missingImgs = utils.imageExistsInAliCrRegistry(namespace: imageDir, ["${project}:${params.BRANCH}"])

                        // 如果发现有镜像存在，则打印告警信息
                        if (missingImgs.size() == 0) {
                            utils.alert(type: "error", message: "检测到镜像 ${project}:${params.BRANCH} 已经存在，禁止使用相同tag构建镜像")
                            error "检测到镜像 ${project}:${params.BRANCH} 已经存在，禁止使用相同tag构建镜像"
                        }
                    }
                }
            }
        }
        stage('打包docker镜像'){
            when {
                expression {
                    return params.环境 && params.BRANCH && (params.Deploy_dev_K8s || params.Push_prod_docker_img)
                }
            }
            steps {
                script {
                    utils.runWithNotify("Docker镜像构建失败,请联系运维处理") {
                        container('docker') {
                            withCredentials([string(credenTialsID: utils.gitlabApiTokenCertID, variable: 'GITLAB_API_TOKEN')]) {
                                utils.genInfraFile(src: "Dockerfile/go-biz/${project}/Dockerfile", output: "./Dockerfile")
                                sh """
                                    docker build --build-arg gitToken=${GITLAB_API_TOKEN} --pull -t ${imageName}:${BUILD_NUMBER} .
                                """
                            }
                        }
                    }
                }
            }
        }
        stage('推送镜像到harbor') {
            when {
                expression {
                    return params.环境 && params.BRANCH && params.Deploy_dev_K8s
                }
            }
            steps {
                script {
                    utils.runWithNotify("docker镜像推送失败，请联系运维处理") {
                        container('docker') {
                            utils.withInternalDockerRegistry {
                                sh """
                                    docker push ${imageName}:${BUILD_NUMBER}
                                """
                            }
                        }
                    }
                }
            }
        }
        stage('推送镜像到体验生产环境') {
            when {
                expression { return params.环境 && params.BRANCH && params.Push_prod_docker_img }
            }
            steps {
                script {
                    utils.runWithNotify("docker镜像推送失败，请联系运维处理") {
                        container('docker') {
                            utils.withInternalDockerRegistry {
                                sh """
                                    docker tag ${imageName}:${BUILD_NUMBER} ${utils.aliSgCrRegistryHost}/${imageDir}/${project}:${params.BRANCH}
                                    docker push ${utils.aliSgCrRegistryHost}/${imageDir}/${project}:${params.BRANCH}
                                """
                            }
                        }

                    }
                }
            }
        }
        stage('部署到k8s') {
            parallel {
                stage("部署go-biz-finance-center") {
                    when {
                        expression {
                            return params.环境 && params.BRANCH && params.Deploy_dev_K8s;
                        }
                    }
                    steps {
                        script {
                            utils.runWithNotify("部署到k8s失败，请研发同学点上面的 deploy-go 任务 ID 链接进入查看失败详情") {
                                build wait: true, job: 'go-biz-deploy-canary', parameters: [
                                    string(name: '环境', value: params.环境),
                                    string(name: '服务名', value: project),
                                    string(name: '金丝雀版本', value: params["金丝雀版本"]),
                                    string(name: 'image_tag', value: BUILD_NUMBER),
                                    string(name: 'helm_chart', value: "go-biz/${project}"),
                                ]
                            }
                        }
                    }
                }
                stage("部署go-biz-finance-center-internal") {
                    when {
                        expression {
                            return params.环境 && params.BRANCH && params.Deploy_dev_K8s;
                        }
                    }
                    steps {
                        script {
                            utils.runWithNotify("部署到k8s失败，请研发同学点上面的 deploy-go 任务 ID 链接进入查看失败详情") {
                                build wait: true, job: 'go-biz-deploy-canary', parameters: [
                                    string(name: '环境', value: params.环境),
                                    string(name: '服务名', go-biz-finance-center-internal),
                                    string(name: '金丝雀版本', value: params["金丝雀版本"]),
                                    string(name: 'image_tag', value: BUILD_NUMBER),
                                    string(name: 'helm_chart', value: "go-biz/${project}"),
                                ]
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                def envMap = [
                    'dev-public': '开发',
                    'test-public': '测试'，
                ]

                def meta = ["#${BUILD_NUMBER}", envMap[params['环境']], params.BRANCH] - null
                buildName meta.join(" - ")
            }
        }
    }
}