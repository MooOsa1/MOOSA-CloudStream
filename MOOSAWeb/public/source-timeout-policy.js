/** Longer web deadline only for a configured single-film JVM pilot. */
export function sourceLookupBudgetMs(media,pilotReady=false){
  return media==='movie' && pilotReady===true ? 75000 : 16500;
}
