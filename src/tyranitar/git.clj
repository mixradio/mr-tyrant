(ns tyranitar.git
  (:require [environ.core :refer [env]]
            [clojure.java.io :refer [as-file]]
            [clojure.tools.logging :as log]
            [clj-time.coerce :refer [from-long]]
            [clj-time.format :refer [unparse formatters]]
            [cheshire.core :refer [parse-string]]
            [me.raynes.conch :as conch])
  (:import [org.eclipse.jgit.api Git]
           [org.eclipse.jgit.api.errors InvalidRemoteException]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.treewalk TreeWalk]
           [org.eclipse.jgit.transport JschConfigSessionFactory SshSessionFactory]
           [com.jcraft.jsch JSch]
           [java.io ByteArrayInputStream]))

;; Reinstate this if you want low-level logging of GIT operations.
;; (def ^{:dynamic true}
;;   ssh-log-levels
;;   (atom
;;    {com.jcraft.jsch.Logger/DEBUG :trace
;;     com.jcraft.jsch.Logger/INFO :debug
;;     com.jcraft.jsch.Logger/WARN :warn
;;     com.jcraft.jsch.Logger/ERROR :error
;;     com.jcraft.jsch.Logger/FATAL :fatal}))

;; (deftype SshLogger
;;    [log-level]
;;    com.jcraft.jsch.Logger
;;    (isEnabled
;;     [_ level]
;;     (>= level log-level))
;;    (log
;;     [_ level message]
;;     (logging/log "clj-ssh.ssh" (@ssh-log-levels level) nil message)))

;; (JSch/setLogger (SshLogger. com.jcraft.jsch.Logger/DEBUG))

; The private key of the tyranitar-bot installed in SNC. Shouldn't change.
(def tyranitar-private-key
  "-----BEGIN RSA PRIVATE KEY-----
MIIEoQIBAAKCAQEA0URSQjQT7uYXG42k3X9hvKPvD1SQQBRQImmoGRh2xBL8k7In
7IRMpl1SBXIPB9dlboOfbPwGqswlr+GtD1Q7+K3hAWC1BrjuYw/khhGC18Gc5rq3
dKc0ndfvJrzpuEoMBGgG6rUQXSo/Ll5QeYMZ8a9kTY51QgsxBah2eT45H+LW4gXt
qHEgrgI0qdWXRr2wPREg/3oxLYX03kagGDO/AdQTKcMqndBSs3PdaNOcn4w9uLnC
ZjYI9Msj6G4bKSJMs1kw6tLCzfX95EKIWYiEWBmHSpDWlHx1mc+ZAosKh/Jgh5UU
rO5epG+iix0cKGj4VsPXs8S6zU0vJ80JvQ3z9wIBIwKCAQEAy0mu/nu7l52wDCMy
cMTimf5V8aKawefY0PjscCZkvnjYGnKNLubrX8heTm7MxcntgUypf9BA/bBfH+KK
27DpzQCusOGZ6UXu4+ONiY1aiHLwMJgf7am+Fa0xdhaZ5jlNgJ+RsM0l1tiVJbq0
k0wn1NZELhVb9wOOtQoMsEsS68Sopt2fKeVfCbVy09F6gWv/cb4rWBhEhswLprxc
pcHR6ABq/B9vGy45Fos7hro910rOkuEgtY28RfvGlsSjdkYDy9aXmgiKd6lvhAEX
ZAVTUe17GsssMvHSNukjs6f4OFUsra2YoTghFQhm8Y3iBUSQYLcsqPAAoD3q4WI3
+SQlOwKBgQDoaFPs/llg3308uCf/iHco+Cp3nUheh3J/AUtPQKWE6kABR7V7SUfE
f7zJaqhXPxkeYERhb5Cs2rQF4+ov2ynnTWbb4wQc3u7+nd5hZC6S+0hbA/E+J+1N
XBO0cMl5wB9+lwwP4fxg8aaAUb7zXZjUuNvgv5VI6K7xf77zlSrHFQKBgQDmgqCv
pJySre/UkBm3wJOW5map8OuqOW6XC7HiaSiWt8/f/KIIQBU1UTbPL7bYilrSObrO
Wd6/8CqGSzH3vaFDX2hAf9TqN5xXxgis/1R18eQ8m/ufoYcqMmJ8DYWYyhHKiQsF
yqGfaG2pMaZ01HL4Z4XBc3SebNb5C1Yn1JhR2wKBgE+uvbBXNJY9/w2A98VTXA4L
8U2VAt6U1sx1eOf46ErT+K/7VCpFAqnFZUxfFSU6NH9xdofOer7r5fNkFcdD09pG
bGilmwKVk8UC7Si0okEFsmhZH4MGX/1EJANZ3q1mcTK3cdmPTzcuR7b3dKs2CINy
lIeSJI4GomFtoI4HQd3bAoGAE8INxemJw23+nTDsQvM/2bv6K9LSXww8rdxuVTw2
rdU9tBz38hQfEzLYz+4lnYgzq56MWtSAzp+OncvuVxBBBcZZZJ0+IrRPOrkz8ZI6
cIJyphv/n/fnA53rLzRb6IZZS9/cX5UGigGi/9+vLpXd+AjflD0Yn91xgw+Zq6SC
FaUCgYBU1g2ELThjbyh+aOEfkRktud1NVZgcxX02nPW8php0B1+cb7o5gq5I8Kd8
0aWoUVpKvv9gz1M0jfgQaL2nXGY1bNiq7uocGbVDDzd8c5Gj7gHKWdObDbgoEcL/
0cxpDojs3XizG/0FEd4J9UscdrrqHWFZ1grLNYd3orcxhDcpcg==
-----END RSA PRIVATE KEY-----")

;; The known-hosts, i.e. source.nokia.com
(def known-hosts
  "|1|UoVqPabY168wScQJfyEUyDX35Xk=|DTUa0H6lR05jNuvHIMl4ReJLqXM= ssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEAuK96oIAr4mPDxbiJqlSi7KFM9GY1jnzb+LhZlJyvJRqK925hgEdTS/QG4uoH4VI0NqMWiCLn8LiPLyj2+WLnYBWpaPIsp728ighAahYY1TsZiUiP4EqpRd093Ur+EE+de7cjfuNy5iJfkU092SqLUJwQCMA05N9vvkSc0lR/hOR77bs/YLucaGyZfXGfHFbosd4+sm82hcqLJKIdQ0+ChEp3ROyZnzferlKqJbFFjJdN4TTq3ITPNjmQ1Hqmmb0kjBJ6M8W11SgqANjdzfnkXHhV46rYrjXesxoPxw3jS1BPEjbLljrY1NMBMhFOLI6tlvFTJc5Jk7c7ytmtG5+sCQ==
|1|xtbIYF+FIx2dSIOML++8N0Ohwuw=|f11MX7uxFmdYTaPNxh961FunJI0= ssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEAuK96oIAr4mPDxbiJqlSi7KFM9GY1jnzb+LhZlJyvJRqK925hgEdTS/QG4uoH4VI0NqMWiCLn8LiPLyj2+WLnYBWpaPIsp728ighAahYY1TsZiUiP4EqpRd093Ur+EE+de7cjfuNy5iJfkU092SqLUJwQCMA05N9vvkSc0lR/hOR77bs/YLucaGyZfXGfHFbosd4+sm82hcqLJKIdQ0+ChEp3ROyZnzferlKqJbFFjJdN4TTq3ITPNjmQ1Hqmmb0kjBJ6M8W11SgqANjdzfnkXHhV46rYrjXesxoPxw3jS1BPEjbLljrY1NMBMhFOLI6tlvFTJc5Jk7c7ytmtG5+sCQ==
")

(def base-git-url (env :service-base-git-repository-url))
(def base-git-path (env :service-base-git-repository-path))

(def git-timeout 60000)

(def my-jcs-factory
  (proxy [JschConfigSessionFactory] []
    (configure [host session]
      (log/info "Configuring JschConfigSessionFactory.")
      (.setConfig session "StrictHostChecking" "yes"))
    (createDefaultJSch [fs]
      (let [jsch (JSch.)]
        (log/info "Creating default JSch using tyranitar private key and known-hosts.")
        (.addIdentity jsch "tyranitar" (.getBytes tyranitar-private-key) nil nil)
        (.setKnownHosts jsch (ByteArrayInputStream. (.getBytes known-hosts)))
        jsch))))

(SshSessionFactory/setInstance my-jcs-factory)

(conch/programs rm)

(defn repo-url
  [repo-name]
  (str base-git-url repo-name))

(defn repo-path
  [name]
  (str base-git-path name))

(defn clone-repo
  "Clones the latest version of the specified repo from GIT."
  [repo-name]
  (log/info "First ensuring that repository directory does not exist")
  (rm "-rf" (repo-path repo-name))
  (log/info "Cloning repository to" (repo-path repo-name))
  (->
   (Git/cloneRepository)
   (.setURI (repo-url repo-name))
   (.setDirectory (as-file (repo-path repo-name)))
   (.setRemote "origin")
   (.setBranch "master")
   (.setBare false)
   (.setTimeout git-timeout)
   (.call))
  (log/info "Cloning completed."))

(defn pull-repo
  "Pull a repository by fetching then merging. Assumes no merge conflicts which should be OK as the repository will only ever be touched via this route."
  [repo-name]
  (let [git (Git/open (as-file (repo-path repo-name)))]
    (log/info "Fetching repository to" (repo-path repo-name))
    (->
     (.fetch git)
     (.setTimeout git-timeout)
     (.call))
    (log/info "Fetch completed.")
    (let [repo (.getRepository git)
          origin-master (.resolve repo "origin/master")]
      (log/info "Merging origin/master.")
      (->
       (.merge git)
       (.include origin-master)
       (.call))
      (log/info "Merge completed."))))

(defn get-exact-commit
  "Get the hash and the data contained in the file for the category file in the chosen repository at the specified
   commit level from GIT. Will accept the same commit identifiers as GIT."
  [repo-name category commit]
  (log/info "Attempting to get exact commit" commit "in" repo-name "for category" category)
  (let [git (Git/open (as-file (repo-path repo-name)))
        repo (.getRepository git)
        commit-id (.resolve repo commit)
        rwalk (RevWalk. repo)
        commit (.parseCommit rwalk commit-id)
        tree (.getTree commit)
        twalk (TreeWalk/forPath repo (str category ".json") tree)
        loader (.open repo (.getObjectId twalk 0))
        text-result (slurp (.openStream loader))]
    (log/info "Commit with hash" (.getName commit-id) "obtained")
    {:hash (.getName commit-id)
     :data (parse-string text-result true)}))

(defn- commit-to-map
  "Turns a commit object into a useful map."
  [commit]
  {:hash (.getName commit)
   :committer (.getName (.getCommitterIdent commit))
   :email (.getEmailAddress (.getCommitterIdent commit))
   :message (.getShortMessage commit)
   :date (unparse (:date-time-no-ms formatters) (from-long (* (.getCommitTime commit) 1000)))})

(defn fetch-recent-commits
  "Get the 20 most recent commits to the repository."
  [repo-name]
  (log/info "Attempting to get recent commits for repository" repo-name)
  (let [git (Git/open (as-file (repo-path repo-name)))
        commits (->
                 (.log git)
                 (.setMaxCount 20)
                 (.call))]
    {:commits (reduce (fn [v i] (conj v (commit-to-map i))) [] commits)}))

(defn commit-and-push
  [repo-name message]
  (let [git (Git/open (as-file (repo-path repo-name)))
        add (.add git)
        commit (.commit git)
        push (.push git)]
    (->
     add
     (.addFilepattern ".")
     (.call))
    (->
     commit
     (.setAuthor "tyranitar" "noreply@nokia.com")
     (.setMessage message)
     (.call))
    (->
     push
     (.setTimeout git-timeout)
     (.call))))
