/*
 * NOVA Tool Registry · runs fully offline in the Android WebView.
 * Capability questions are answered from REAL Capacitor plugins, not from
 * a language model guessing what it can do. Actual tool execution stays
 * in the owning native plugin and retains owner-consent / memory gates.
 */
(function (root) {
  'use strict';

  const IMAGE_TOPIC = /(?:картин(?:к|ок)|изображени|фото|фотограф|фотк|рисунк|рисова|нарис|малюн|зображенн|намалю|малюва|image|picture|draw|generat.{0,5} imag)/iu;
  const VIDEO_TOPIC = /(?:видео|видеоролик|відео|ролик|монтаж|video|edit.{0,5} video)/iu;
  const ABILITY = /(?:\?|умеешь|умеєш|можешь|можеш|можно|можна|способна|возможно|зможеш|умеет|вмієш|can you|could you|способност|возможност|вміє|уміє)/iu;
  const TOOL_REFERENCE = /(?:инструмент|інструмент|с помощью|за допомогою|через программу|через програму|tool)/iu;
  const GENERAL_ABILITY = /(?:что ты умеешь|что умеешь|твои возможности|твои инструменты|що ти вмієш|які в тебе можливості|what can you do)/iu;

  function recentImageContext(history) {
    const recent = Array.isArray(history) ? history.slice(-6) : [];
    return recent.some(entry => entry && entry.who === 'user' &&
      typeof entry.text === 'string' && IMAGE_TOPIC.test(entry.text));
  }

  function classifyCapability(message, history) {
    if (typeof message !== 'string') return null;
    const text = message.trim();
    if (!text) return null;
    if (IMAGE_TOPIC.test(text) && ABILITY.test(text))
      return { kind: 'capability', tool: 'image' };
    // A follow-up such as "А если с помощью инструментов?" must retain
    // the user's recent image topic instead of letting Gemma guess.
    if (TOOL_REFERENCE.test(text) &&
        (ABILITY.test(text) || /^(?:а |а если|а через|і якщо|а с )/iu.test(text))) {
      return {
        kind: 'capability',
        tool: recentImageContext(history) ? 'image' : 'all'
      };
    }
    if (VIDEO_TOPIC.test(text) && ABILITY.test(text))
      return { kind: 'capability', tool: 'video' };
    if (GENERAL_ABILITY.test(text))
      return { kind: 'capability', tool: 'all' };
    return null;
  }

  async function getImageStatus(plugins) {
    const image = plugins && plugins.NovaImage;
    if (!image || typeof image.status !== 'function')
      return { available: false, detail: 'Нативный графический модуль не найден в этой сборке.' };
    try {
      const result = await image.status();
      return {
        available: true,
        ready: !!result.ready,
        tested: !!result.testedOnDevice,
        detail: typeof result.detail === 'string' ? result.detail : ''
      };
    } catch (error) {
      return {
        available: false,
        detail: 'Не удалось проверить графический модуль: ' +
          (error && error.message ? error.message : String(error))
      };
    }
  }

  async function describeCapability(route, plugins) {
    if (!route || route.kind !== 'capability') return null;
    const image = await getImageStatus(plugins);
    const text = !!(plugins && plugins.NovaBrain);
    const council = !!(plugins && plugins.NovaBrain &&
      typeof plugins.NovaBrain.directorCouncil === 'function');
    const tools = !!(plugins && plugins.NovaTools);

    const imageDescription = !image.available ?
      'Создание изображений сейчас недоступно: ' + image.detail :
      !image.ready ?
      'У меня встроен генератор изображений, но графическая модель ещё не готова. ' +
        (image.detail || 'Добавь модель во вкладке «Мозги».') :
      image.tested ?
      'Да! Я уже создавала PNG прямо внутри NOVA. ' +
        'Могу нарисовать новый кадр локально. Просто напиши: «Нарисуй тёмный лес в тумане».':
      'Да, у меня есть встроенный генератор изображений и выбранная модель. ' +
        'Её работу на этом устройстве ещё нужно проверить. ' +
        'Напиши: «Нарисуй тёмный лес в тумане».';

    if (route.tool === 'image')
      return imageDescription;
    if (route.tool === 'video')
      return 'Я могу разрабатывать сценарии и обсуждать монтаж с режиссёрским советом, ' +
        'но автоматический монтаж видео внутри этой APK ещё не подключён. Не буду делать вид, что уже смонтировала ролик.';
    const parts = [];
    if (text) parts.push('общаюсь через локальную языковую модель');
    if (council) parts.push('обсуждаю сценарии с режиссёрским советом и текстовым Critic');
    if (tools) parts.push('использую инструменты NOVA для разрешённых тобой загрузок');
    parts.push(imageDescription);
    parts.push('Видеомонтаж и офлайн-голос пока не подключены в этой тестовой APK.');
    return parts.join('. ').replace(/\.\./g, '.');
  }

  const api = Object.freeze({classifyCapability, describeCapability, getImageStatus});
  root.NovaToolRegistry = api;
  if (typeof module === 'object' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
