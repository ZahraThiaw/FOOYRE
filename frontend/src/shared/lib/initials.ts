/** Initiales d'un nom (deux au plus), en majuscules ; chaîne vide si le nom est vide. */
export function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter((word) => word !== '');
  const first = words[0];
  if (first === undefined) return '';
  const last = words.length > 1 ? words[words.length - 1] : undefined;
  const initial = (word: string | undefined) => (word === undefined ? '' : (Array.from(word)[0] ?? ''));
  return (initial(first) + initial(last)).toLocaleUpperCase('fr');
}
