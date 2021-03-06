(ns anbf.tracker
  "Tracking and pairing monsters frame-to-frame and their deaths and corpses turn-by-turn"
  (:require [anbf.position :refer :all]
            [anbf.dungeon :refer :all]
            [anbf.delegator :refer :all]
            [anbf.fov :refer :all]
            [anbf.tile :refer :all]
            [anbf.item :refer :all]
            [anbf.itemtype :refer :all]
            [anbf.monster :refer :all]
            [anbf.handlers :refer :all]
            [anbf.level :refer :all]
            [anbf.player :refer :all]
            [anbf.util :refer :all]
            [clojure.tools.logging :as log]))

(defn- transfer-pair [{:keys [player] :as game} [old-monster monster]]
  ;(log/debug "transfer:" \newline old-monster "to" \newline monster)
  (update-curlvl-monster game monster
    #(as-> % monster
       (into monster (select-some old-monster [:type :cancelled :awake]))
       (if (not= :update (:peaceful monster))
         (assoc monster :peaceful (:peaceful old-monster))
         monster)
       (if (not= (position old-monster) (position monster))
         (assoc monster :awake true)
         monster)
       (case (compare (distance-manhattan player old-monster)
                      (distance-manhattan player monster))
         -1 (assoc monster :fleeing true)
         0 (assoc monster :fleeing (:fleeing old-monster))
         1 (assoc monster :fleeing false)))))

(defn filter-visible-uniques
  "If a unique monster was remembered and now is visible, remove all remembered instances"
  [game]
  (let [monsters (curlvl-monsters game)]
    (reduce remove-curlvl-monster game
            (for [m monsters
                  :when ((every-pred unique? (complement :remembered)) m)
                  n (filter #(and (= (typename m) (typename %)) (:remembered %))
                            monsters)]
              (position n)))))

(defn- transfer-unpaired [game unpaired]
  ;(log/debug "unpaired" unpaired)
  ; TODO don't transfer if we would know monsters position with ESP
  (let [tile (at-curlvl game unpaired)]
    (if (visible? game unpaired)
      (if (and (#{\1 \2 \3 \4 \5} (:glyph unpaired))
               ((some-fn stairs? boulder? :new-items) tile)
               (not (monster? tile))) ; maybe a sneaky mimic
        (reset-curlvl-monster game (assoc unpaired :remembered true))
        game)
      game)))

(defn track-monsters
  "Try to transfer monster properties greedily from the old game snapshot to the new, even if the monsters moved slightly."
  [new-game old-game]
  (if (or (not= (:dlvl old-game) (:dlvl new-game))
          (hallu? (:player new-game)))
    new-game ; TODO track stair followers?
    (loop [pairs {}
           new-monsters (:monsters (curlvl new-game))
           old-monsters (:monsters (curlvl old-game))
           dist 0]
      (if (and (> 4 dist) (seq old-monsters))
        (if-let [[p m] (first new-monsters)]
          (if-let [[[cp cm] & more] (seq (filter
                                           (fn candidate? [[_ n]]
                                             (and (= (:glyph m) (:glyph n))
                                                  (= (:color m) (:color n))
                                                  (= (:friendly m)
                                                     (:friendly n))
                                                  (= dist (distance m n))))
                                           old-monsters))]
            (if more ; ignore ambiguous cases
              (recur pairs (dissoc new-monsters p) old-monsters dist)
              (recur (assoc pairs p [cm m])
                     (dissoc new-monsters p)
                     (dissoc old-monsters cp)
                     dist))
            (recur pairs (dissoc new-monsters p) old-monsters dist))
          (recur pairs (apply dissoc (:monsters (curlvl new-game)) (keys pairs))
                 old-monsters (inc dist)))
        (as-> new-game res
          (reduce transfer-unpaired res (vals old-monsters))
          (reduce transfer-pair res (vals pairs)))))))

(defn- mark-kill [game old-game]
  (if-let [dir (and (not (impaired? (:player old-game)))
                    (#{:move :attack} (typekw (:last-action* game)))
                    (:dir (:last-action* game)))]
    (let [level (curlvl game)
          tile (in-direction level (:player old-game) dir)
          old-monster (or (monster-at (curlvl old-game) tile)
                          (unknown-monster (:x tile) (:y tile) (:turn game)))]
      (if (or (item? tile) (monster? tile) (pool? tile) ; don't mark deaths that clearly didn't leave a corpse
              (blind? (:player game)))
        (update-curlvl-at game tile mark-death old-monster (:turn game))
        game))
    game))

(defn death-tracker [anbf]
  (let [old-game (atom nil)]
    (reify
      ActionChosenHandler
      (action-chosen [_ _]
        (reset! old-game @(:game anbf)))
      ToplineMessageHandler
      (message [_ msg]
        (if (and @old-game (not (hallu? (:player @old-game))))
          (condp re-first-group msg
            #"You (kill|destroy) [^.!]*[.!]"
            (update-before-action anbf mark-kill @old-game)
            ;#" is (killed|destroyed)" ...
            nil))))))

(defn- only-fresh-deaths? [tile corpse-type turn]
  (let [relevant-deaths (remove (fn [[death-turn
                                      {monster-type :type :as monster}]]
                                  (and (< 500 (- turn death-turn))
                                       monster-type
                                       (:name monster-type)
                                       (or (not (.contains (:name monster-type)
                                                           (:name corpse-type)))
                                           (not (.contains (:name monster-type)
                                                           " zombie")))
                                       (not= corpse-type monster-type)))
                                (:deaths tile))
        unsafe-deaths (filter (fn [[death-turn _]] (<= 30 (- turn death-turn)))
                              relevant-deaths)
        safe-deaths (filter (fn [[death-turn _]] (> 30 (- turn death-turn)))
                            relevant-deaths)]
    (and (empty? unsafe-deaths)
         (seq safe-deaths))))

(defn fresh-corpse?
  "Works only for corpses on the ground that haven't been moved"
  [game pos item]
  (if-let [corpse-type (and (corpse? item) (single? item)
                            (:monster (name->item (:name item))))]
    (or (:permanent corpse-type)
        (and (only-fresh-deaths?
               (at-curlvl game pos) corpse-type (:turn game))))))
