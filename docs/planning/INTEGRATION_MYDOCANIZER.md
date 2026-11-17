# Integration in myDocAnizer-Mobile — zurückgestellt

Die konkrete Analyse und Planung der Integration wird bewusst zurückgestellt.

**Aktueller Auftrag:** Zuerst muss die Engine-Logik in :llm-engine selbst vollständig geprüft, korrigiert, optimiert und für den vorgesehenen produktiven Einsatz gehärtet werden. Dazu gehören Inferenzkorrektheit, unterstützte Modellfamilien, CPU-/Vulkan-Verhalten, Abbruch/Recovery, Modellquellen, Speicher, Leistung und Codequalität.

Bis zu einem ausdrücklichen GO des Nutzers werden:
- keine aktuellen Codevergleiche zwischen Local_LLM_Engine und myDocAnizer_Mobile durchgeführt,
- keine Integrationshindernisse oder erforderlichen Änderungen in einem der beiden Repositories analysiert,
- keine Consumer-Schnittstellen- oder Migrationsreihenfolge festgelegt,
- keine Integrationsaufgaben in die aktive Master-TODO-Liste aufgenommen.

Nach dem GO wird die dann aktuelle Version beider Repositories neu geprüft. Frühere Integrationsnotizen oder Commit-Stände gelten bis dahin nicht als aktuelle technische Grundlage.

Die einzige aktive Aufgabenliste für die laufende Arbeit ist [Master-TODO](../active-todos/MASTER_TODO.md).
