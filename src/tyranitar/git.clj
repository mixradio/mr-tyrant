(ns tyranitar.git
  (:require [environ.core :refer [env]]
            [clojure.java.io :refer [as-file]]
            [clj-time.coerce :refer [from-long]]
            [clj-time.format :refer [unparse formatters]]
            [clojure.string :refer [upper-case]]
            [cheshire.core :refer [parse-string]])
  (:import [org.eclipse.jgit.api Git MergeCommand MergeCommand$FastForwardMode]
           [org.eclipse.jgit.api.errors InvalidRemoteException]
           [org.eclipse.jgit.revwalk RevWalk]
           [org.eclipse.jgit.treewalk TreeWalk]
           [java.io FileNotFoundException]))

(def base-git-url (env :service-base-git-repository-url))
(def base-git-path (env :service-base-git-repository-path))

(defn- repo-url
  [repo-name]
  (str base-git-url repo-name))

(defn- repo-path
  [name]
  (str base-git-path name))

(defn clone-repo
  "Clones the latest version of the specified repo from GIT."
  [repo-name]
  (->
   (Git/cloneRepository)
   (.setURI (repo-url repo-name))
   (.setDirectory (as-file (repo-path repo-name)))
   (.setRemote "origin")
   (.setBranch "master")
   (.setBare false)
   (.call)))

(defn- pull-repo
  "Pull a repository by fetching then merging. Assumes no merge conflicts which should be OK as the repository will only ever be touched via this route."
  [repo-name]
  (let [git (Git/open (as-file (repo-path repo-name)))]
    (->
     (.fetch git)
     (.call))
    (let [repo (.getRepository git)
          origin-master (.resolve repo "origin/master")]
      (->
       (.merge git)
       (.include origin-master)
       (.call)))))

(defn get-exact-commit
  "Get the hash and the data contained in the file for the category file in the chosen repository at the specified 
   commit level from GIT. Will accept the same commit identifiers as GIT."
  [repo-name category commit]
  (let [git (Git/open (as-file (repo-path repo-name)))
        repo (.getRepository git)
        commit-id (.resolve repo commit)
        rwalk (RevWalk. repo)
        commit (.parseCommit rwalk commit-id)
        tree (.getTree commit)
        twalk (TreeWalk/forPath repo (str category ".json") tree)
        loader (.open repo (.getObjectId twalk 0))
        text-result (slurp (.openStream loader))]
    {:hash (.getName commit-id)
     :data (parse-string text-result)}))

(defn- commit-to-map
  "Turns a commit object into a useful map."
  [commit]
  {:hash (.getName commit)
   :committer (.getName (.getCommitterIdent commit))
   :email (.getEmailAddress (.getCommitterIdent commit))
   :message (.getShortMessage commit)
   :date (unparse (:date-time-no-ms formatters) (from-long (* (.getCommitTime commit) 1000)))})

(defn- get-recent-commits-list
  "Get the 20 most recent commits to the repository."
  [repo-name]
  (let [git (Git/open (as-file (repo-path repo-name)))
        commits (->
                 (.log git)
                 (.setMaxCount 20)
                 (.call))]
    {:commits (reduce (fn [v i] (conj v (commit-to-map i))) [] commits)}))

(defn- repo-exists?
  [repo-name]
  (.exists (as-file (repo-path repo-name))))

(defn- ensure-repo-up-to-date
  "Gets or updates the specified repo from GIT"
  [repo-name]
  (if (repo-exists? repo-name)
    (pull-repo repo-name)
    (clone-repo repo-name)))

(defn get-data
   "Fetches the data corresponding to the given params from GIT"
  [env app commit category]
  (let [repo-name (str app "-" env)]
    (try
      (ensure-repo-up-to-date repo-name)
      (get-exact-commit repo-name category (upper-case commit))
      (catch InvalidRemoteException e
        nil)
      (catch NullPointerException e
        nil))))

(defn get-list
  "Get a list of the 20 most recent commits to the repository in most recent first order."
  [env app]
  (let [repo-name (str app "-" env)]
    (try
      (ensure-repo-up-to-date repo-name)
      (get-recent-commits-list repo-name)
      (catch InvalidRemoteException e
        nil))))
