import java.net.URL.Encoder
import groovy.transform.Field
import java.util.Calendar

// Git凭证ID
@Field String gitCertID = "gitlab-pipeline"

// Gitlab API Token,通过gitlab API下载文件时需要
@Field String gitlabApiTokenCertID = "gitlab-api-token"

// infra仓库地址
@Field String infraRepo = "http://172.22.203.145:8100/root/infra.git"

//helm仓库地址
@Field String internalChartRepo = "http://172.22.203.145:8100/root/helm.git"

//harbor仓库域名
@Field String internalRegitstryHost = "harbor.mdz.com"

//harbor仓库凭证ID
@Field String internalRegitstryCertID = "internal-registry"

/**
*
*打印比较醒目的提示信息
*
* @param opts Map 参数，包含 type,message
* @param opts.type String 消息类型，合法值为 success，waining，error
* @param opts.message String 为消息内容
**/

def alert(Map opts) {
    if (!["success", "waining", "error"].contains(opts.type)) {
        error "方法 alert 参数不合法, type必须提供, 且值只能为 success, waining和error, 当前值为 type: ${opts.type}"
    }

    if (!opts.message) {
        error "方法 alert 参数不合法, message 为必传参数"
    }

    colorEnd = "\033[0m"
    colorBegin = ""
    switch(opts.type) {
        case "SUCCESS":
            colorBegin = "\033[32m" //绿色
            break
        case "warning":
            colorBegin = "\033[33m" //黄色
            break
        case "error":
            colorBegin = "\033[31m" //红色
            break
        default:
            colorBegin = "\033[33m" //默认黄色
            break
    }

    ansiColor('xterm') {
        sh """
            set +x
            echo -e "
            ${colorBegin}====================================================================${colorEnd}
            ${colorBegin} ${opts.message} ${colorEnd}
            ${colorBegin}====================================================================${colorEnd}\n\n"
        """
    }
}

/**
*
* 执行出错时，展示排错指引提醒
*
* @param message String提示信息
* @param cl Closure 具体执行的代码块
*/

def runWithNotify(String message, Closure cl) {
    try {
        cl()
    } catch (err) {
        alert(
            type: "error",
            message: "${message}\n\033[31m 若需向运维反馈, 请务必提供构建链接 ${BUILD_UEL} console\033[0m"
        )
        throw err
    }
}

/**
*
* 检出(checkout) git 仓库
*
* @param opts Map 参数，包含 type，message
* @param opts.repoUrl String 要检出git仓库地址
* @param opts.branch String 可选, 要检出的分支(branch)或标签（tag）,默认为“master”
* @param opts.messageOnFailed String 可选，拉取失败时的错误提示信息，默认为"拉取代码失败，请在尝试一次，若依然失败请联系运维处理"
* @param opts.certID String 可选，凭据ID，默认值为'utils.gitCertID'
* @param opts.noTags boolean 可选，检出时是否不包含tags,以加快检出速度，默认值为false
* @param opts.extensions ArrayList 可选，可配置附加行为，注意：'CloneOption' 已经存在，无法额外添加
*/

def gitCheckout(Map opts = [:]) {
    if (!opts.repoUrl) {
        error "方法 gitCheckout 参数不合法, repoUrl 为必传参数,当前值repoUrl: ${opts.repoUrl}"
    }
    def branch = opts.branch ?: "master"
    def failedMessage = opts.messageOnFailed ?: "拉取代码失败，请再次尝试，若依然失败，请联系运维处理"
    def noTags = opts.noTags ?: false
    def certID = opts.certID ?: gitCertID
    def extensions = opts.extensions ?: new ArrayList([])
    
    extensions.add([$class: 'CloneOption', depth: 1, noTags: noTags, reference: '', shallow: true, timeout: 120])

    runWithNotify(failedMessage) {
        checkout([
            $class: 'GitSCM',
            branches: [[name: branch]],
            doGenerateSubmoduleConfigurations: false,
            extensions: extensions,
            userRemoteConfigs: [[
                credentialsID: certID,
                url: opts.repoUrl,
            ]],
        ])
    }
}

/*
*
* 在侧边栏添加链接
* 
* @param opts Map 参数包含 url, text, icon
* @param opts.url String 链接URL
* @param opts.text String 链接显示文字
* @param opts.icon String Icon图标地址，相当于 `JENKINS_HOME`(/var/lib/jenkins) 的路径, **使用前须先上传图片到配置的路径**
*/
def addSidebarLink(Map opts = [:]) {
    def action = new hudson.plugins.sidebar_link.LinkAction(opts.url, opts.txt, opts.icon)
    currentBuild.rawBuild.addAction(action)
}


/*
*
* 部署前，根据helm release 状态进行前置处理：
*   1. 当状态为`pending-upgrade`时，进行回滚
*   2. 当状态为`pending-install`时，进行卸载
*
* @param opts Map 参数，包含namespace,service,kubeconfig
* @param opts.namespace String 为k8s命名空间
* @param opts.service String 为在k8s上的服务名
* @param opts.kubeconfig String 指定k8s认证文件路径，如果不传，则默认值为 `~/.kube/confgi`
*/
def helmRollbackPending(Map opts = [:]) {
    if (!opts.namespace || !opts.service) {
        error "方法 helmRollbackPending 参数不合法, namespace 和 service为必传参数, 当前值 namespace: ${opts.namespace}, service: ${opts.service}"
    }
    //kubeconfig 文件路径
    def kubeconfig = opts.kubeconfig ?: "~/.kube/config"
    def helmArgs = "--kubeconfig=${kubeconfig} --namespace=${opts.namespace}"

    //获取release状态
    def cmd = "helm status ${opts.service} ${helmArgs} | aws 'NR==4 {print \$2}'"
    def status = sh([returnStdout: true, script: cmd]).trim()
    //状态可参考：https://github.com/helm/blob/main/pkg/release/status.go#L24-L41
    switch(status) {
        case ["pending-upgrade", "pending-rollback"]: //若状态为"pending-upgrade"则回滚
            sh "helm rollback ${opts.service} ${helmArgs} --wait --timeout=5m0s"
            branch
        case ["pending-install", "uninstalling"]: //若状态为pending-install/uninstalling则卸载
            sh "helm uninstall ${opts.service} ${helmArgs} --wait --timeout=5m0s"
            branch
        default:
            echo "当前helm release状态为: ${status}，不做任何处理"
    }
}

/*
*
* 检查镜像仓库是否存在某个镜像
*
* @param opts Map 参数，其中包括certID，registryHost 和 namespace
* @param opts.certID String docker 登陆凭证ID，默认值为 `utils.aliRegistryCertID`
* @param opts.registryHost String docker 仓库域名，默认值为 `utils.aliRegistryHost`
* @param opts.namespace String docker 仓库命名空间，默认值为 `utils.ops`
* @return List 返回不存在的image名称
*/
def imageExistsINAliRegistry(Map opts = [:], List imageNames) {
    def namespace = opts.namespace ?: "saas"
    def registryHost = opts.registryHost ?: aliRegistryHost

    def missingImgs = []
    withAliDockerRegistry(certID: opts.certID, registryHost: registryHost) {
        imageNames.each {
            cmd = "docker manifest inspect ${registryHost}/${namespace}/${imageName}"
            found = sh([returnStatus: true, script: cmd])
            if (found != 0) {
                missingImgs.add(imageName)
            }
        }
    }
}


/*
*
* 按照发布顺序模板，对输入的发布镜像进行排序
*
* 比如，一个模板需遵循的发布顺序时：先发布serviceA，然后在并行发布serviceB 和 serviceC，最后再发布serviceD 和 serviceE
*
* 则顺序模板为：
* orderedPool = [
*    ["serviceA"],
*    ["serviceB", "serivceC"],
*    ["serviceD", "serviceE"]
* ]
*
* 比如某一次发布的清单为一个不符合发布顺序的列表：
* inputImages = [
*    "serviceA:v1.1.1",
*    "serviceB:v1.1.1",
*    "serviceD:v1.1.1",
*    "serviceF:v1.1.1",
* ]
*
*通过本函数处理 reorderImageList(appPool, inputImages),则得到符合发布顺序的列表：
* [
*   ["serviceA:v1.1.1"],
*   ["serviceC:v1.1.1"],
*   ["serviceD:v1.1.1", "serviceF:v1.1.1"]
* ]
*
* @param orderedPool ArrayList 表示有发布顺序的服务名组成的二维数组，子元素数组里的服务可以并行发布
* @param inputImages String[] 要发布的image(带有tag)组成的一维数组
* @param ArrayList 返回排序后的image组成的二维数组
*/

def reorderImageList(ArrayList orderedPool, String[] inputImages) {
    def orderedImgs = []
    def allApps = orderedPool.flatten()

    //先找出不合法的镜像输入
    def invalidInputs = inputImages.findAll{ input ->
        def (appName) = input.trim().split(":")
        !allApps.contains(appName)
    }
    if (invalidInputs.size() > 0) {
        alert(type: "error", message: "发现以下未知镜像，请确认输入的镜像是否正确！ \n${invalidInputs.join('\n')}")
        //如果有不同的，就直接返回
        return orderedImgs
    }

    orderedPool.each { arr ->
        def matches =[]
        arr.each { app ->
            def matched = inputImages.find { v->
                def (appName) = v.trim().split(":")
                return app == appName
            }
            if (matched) {
                matches.add(matched.trim())
            }
        }
        if (matches.size() > 0) {
            orderedImgs.add(matches)
        }
    }
    orderedImgs
}

/**
*
* k8s 停机(replicas=0)后，在执行部署(helm upgrade)后，将pod副本数恢复：
*   1. 如果 hpa 存在，则恢复副本数为 hpa 的minReplicas
*   2. 如果 hpa 不存在，则恢复副 helm chart 中配置的 replicas
*
* @param opts Map 参数，包含 namespace ，service，kubeconfig
* @param opts.namespace String 为k8s命名空间
* @param opts.service String 为在k8s上的服务名
* @param opts.kind String k8s 类型，合法值`deployment`, `deploy`, `statefulset` 和 `sts`, 默认值为`deployment`
* @param opts.kubeconfig String 指定k8s认证文件路径，如果不传，则默认值为 `~/.kube/config`
*/

def k8sReplicaReset(Map opts = [:]) {
    if (!opts.namespace || !opts.service) {
        error "方法 k8sReplicaReset 参数不合法，namespace 和 service 为必传参数，当前值 namespace: ${opts.namespace}, service: ${opts.service}"
    }

    if (opts.kind && !["deployment", "deploy", "statefulset", "sts"].contains(opts.kind)) {
        error "方法 k8sReplicaReset 参数不合法，kind 只能设置为`deployment`, `deploy`, `statefulset` 和 `sts`，当前值为：${opts.kind}"
    }

    //kubeconfig 文件路径
    def kubeconfig = opts.kubeconfig ?: "~/.kube/config"
    def cmdArgs = "--kubeconfig=${kubeconfig} --namespace=${opts.namespace}"

    def kind = opts.kind ?: "deployment"
    def currentReplicasCount = sh(script: "kubectl get ${kind} ${opts.service} ${cmdArgs} -o jsonpath='{.spec.replicas}'", returnStdout: true).trim().toInteger()

    if (currentReplicasCount > 0) {
        echo "${opts.service}服务副本数不为零,无需处理,当前副本数：${currentReplicasCount}"
        return
    }

    // 默认恢复为1个副本：只要不为0副本，hpa就会关闭静默状态，重新被激活
    def replicasCount = "1"
    // 获取原始副本数，如果hpa存在，取hpa的minReplicas, hpa不存在则取helm chart里设置的replicas
    def hpaExist = sh(script: "kubectl get hpa ${opts.service} ${cmdArgs}", returnStatus: true)
    if (hpaExist == 0) {
        replicasCount = sh(script: "kubectl get hpa ${opts.service} ${cmdArgs} -o jsonpath='{.spec.minReplicas}'", returnStdout: true).trim()
    } else {
        def json = sh(script: "helm get values ${opts.service} -a -o json ${cmdArgs}", returnStdout: true).trim()
        def value = readJSON(text: json)
        if (value.replicaCount) {
            replicasCount = value.replicaCount
        }
    }

    echo "${opts.service}服务副本数为0, 开始恢复副本数到：${replicasCount}"
    sh """
        kubectl scale --replicas=${replicasCount} ${kind} ${opts.service} ${cmdArgs}
        kubectl rollout status ${kind} ${opts.service} ${cmdArgs} --timeout=5m
    """

}



