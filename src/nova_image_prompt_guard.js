/* Offline prompt guard for English-centric SD 1.5: never silently paint an unparsed Russian prompt. */
(function (root) {
  'use strict';
  const cyrillic = /[а-яёіїєґ]/iu;
  const englishLetters = /[a-z]/gi;
  const hints = [
    { source: /такс[а-яёіїєґ]*|dachshund|sausage dog/iu,
      accepts: /\b(?:dachshund|sausage dog)\b/i,
      label: 'dachshund dog',
      description: 'a dachshund dog with a long body and short legs' },
    { source: /собак[а-яёіїєґ]*|собач[а-яёіїєґ]*|щен[а-яёіїєґ]*|п(?:ё|е)с(?:ик)?|цуцен[а-яіїєґ]*|\b(?:dog|puppy)\b/iu,
      accepts: /\b(?:dog|dogs|puppy|puppies|canine|hound|dachshund)\b/i,
      label: 'dog', description: 'an adorable dog with clearly visible ears, paws and fur' },
    { source: /кош[а-яёіїєґ]*|кот[а-яёіїєґ]*|к[іи]ш[а-яёіїєґ]*|\b(?:cat|kitten)\b/iu,
      accepts: /\b(?:cat|cats|kitten|kittens|feline)\b/i,
      label: 'cat', description: 'a real cat with visible whiskers, ears and paws' },
    { source: /лошад[а-яёіїєґ]*|кон[ьяіа-яёіїєґ]*|\bhorse\b/iu,
      accepts: /\b(?:horse|horses|pony)\b/i,
      label: 'horse', description: 'a horse with its entire body visible' },
    { source: /птиц[а-яёіїєґ]*|пташ[а-яёіїєґ]*|птах[а-яёіїєґ]*|\bbird\b/iu,
      accepts: /\b(?:bird|birds)\b/i,
      label: 'bird', description: 'a bird with detailed feathers' },
    { source: /цвет[а-яёіїєґ]*|кв[іе]тк[а-яёіїєґ]*|\bflower\b/iu,
      accepts: /\b(?:flower|flowers|blossom)\b/i,
      label: 'flower', description: 'a close-up photograph of flowers' },
    { source: /автомоб[іи]л[а-яёіїєґ]*|машин[а-яёіїєґ]*|\bcar\b/iu,
      accepts: /\b(?:car|vehicle|automobile)\b/i,
      label: 'car', description: 'a car photographed from a three-quarter view' },
    { source: /зеркал[а-яёіїєґ]*|дзеркал[а-яёіїєґ]*|\bmirror\b/iu,
      accepts: /\b(?:mirror|reflection)\b/i,
      label: 'mirror', description: 'a large mirror with a clear reflection' },
    { source: /дерев[а-яёіїєґ]*|дерев[іа-яёіїєґ]*|\btree\b/iu,
      accepts: /\b(?:tree|trees)\b/i,
      label: 'tree', description: 'a tree in a natural setting' }
  ];

  function isEnglishPrompt(value) {
    const text = String(value || '').trim();
    return text.length >= 6 && !cyrillic.test(text) &&
      (text.match(englishLetters) || []).length >= 5;
  }
  function matchesSubject(value) {
    return hints.filter(item => item.source.test(value));
  }
  function isBareSubject(raw) {
    const original = String(raw || '').trim();
    const matched = matchesSubject(original);
    if (!matched.length) return false;
    let remainder = original;
    for (const hint of matched) remainder = remainder.replace(hint.source, ' ');
    return !/[0-9a-zа-яёіїєґ]/iu.test(remainder);
  }
  function fallbackPrompt(hits) {
    const main = hits.find(item => item.label === 'dachshund dog') ||
      hits.find(item => item.label === 'dog') || hits[0];
    const subject = main.description;
    return 'Photorealistic documentary photograph of ' + subject +
      ', subject in the center of the frame, unmistakably visible, ' +
      'natural daylight, accurate shape, clean background, sharp focus, realistic textures';
  }
  function prepare(raw, candidate) {
    const original = String(raw || '').trim();
    const planned = String(candidate || '').trim();
    if (!original) throw new Error('Опиши, что нарисовать.');
    const subjects = matchesSubject(original);
    const preferred = subjects.some(item => item.label === 'dachshund dog')
      ? subjects.filter(item => item.label !== 'dog') : subjects;
    const hasEnglish = isEnglishPrompt(planned);
    if (hasEnglish && preferred.every(item => item.accepts.test(planned))) {
      return { prompt: planned, subject: preferred.map(item => item.label).join(', '),
        fallback: false, translated: original !== planned };
    }
    if (cyrillic.test(original)) {
      // A short, recognized request can be converted safely without another model.
      // For longer/unrecognized descriptions, abort rather than spend 20 minutes on unrelated scenery.
      if (preferred.length && isBareSubject(original)) {
        return { prompt: fallbackPrompt(preferred),
          subject: preferred.map(item => item.label).join(', '), fallback: true, translated: true };
      }
      throw new Error('NOVA не смогла надёжно перевести описание для SD 1.5. Проверь, что Gemma подключена, и повтори запрос. Рисование не запущено.');
    }
    if (isEnglishPrompt(original)) {
      if (preferred.length && preferred.some(item => !item.accepts.test(original))) {
        throw new Error('Не удалось сохранить главный объект изображения.');
      }
      return { prompt: original, subject: preferred.map(item => item.label).join(', '),
        fallback: false, translated: false };
    }
    throw new Error('Не удалось подготовить описание картинки на английском языке.');
  }
  root.NovaImagePromptGuard = Object.freeze({ prepare, isEnglishPrompt, matchesSubject, isBareSubject });
})(typeof window !== 'undefined' ? window : globalThis);
