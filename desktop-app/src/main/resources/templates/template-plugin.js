/**
 * CloudStream Native JavaScript Plugin Template
 * 
 * Drop this file into %APPDATA%/Cloudstream/plugins/js/
 */
export default class MyJsProvider {
    name = "Example Provider";
    mainUrl = "https://example.com";
    supportedTypes = ["Movie", "TvSeries", "Anime"];

    /**
     * 1. Search Query
     * @param {string} query - The search keyword
     * @param {number} page - Current page number
     * @returns {Promise<Array<{name: string, url: string, posterUrl?: string, type?: string}>>}
     */
    async search(query, page = 1) {
        const res = await fetch(`${this.mainUrl}/search?q=${encodeURIComponent(query)}&page=${page}`);
        const $ = cheerio.load(await res.text());
        return $('.search-card').map((_, el) => ({
            name: $(el).find('.title').text().trim(),
            url: $(el).find('a').attr('href'),
            posterUrl: $(el).find('img').attr('src'),
            type: $(el).hasClass('series') ? "TvSeries" : "Movie"
        })).get();
    }

    /**
     * 2. Home Page Catalog
     * @param {number} page - Current page number
     * @returns {Promise<Array<{name: string, url: string, posterUrl?: string, type?: string}>>}
     */
    async getMainPage(page = 1) {
        const res = await fetch(`${this.mainUrl}/trending/page/${page}`);
        const $ = cheerio.load(await res.text());
        return $('.item-card').map((_, el) => ({
            name: $(el).find('.title').text().trim(),
            url: $(el).find('a').attr('href'),
            posterUrl: $(el).find('img').attr('src'),
            type: "Movie"
        })).get();
    }

    /**
     * 3. Details & Episode Lists
     * @param {string} url - Target media URL
     * @returns {Promise<{name: string, posterUrl?: string, plot?: string, type?: string, episodes?: Array}>}
     */
    async load(url) {
        const res = await fetch(url);
        const $ = cheerio.load(await res.text());
        const title = $('h1.entry-title').text().trim();
        const poster = $('.poster-image img').attr('src');
        const synopsis = $('.description-content').text().trim();

        const episodes = $('.episode-entry').map((i, el) => ({
            name: $(el).find('.ep-title').text().trim() || `Episode ${i + 1}`,
            season: parseInt($(el).attr('data-season')) || 1,
            episode: parseInt($(el).attr('data-episode')) || (i + 1),
            data: $(el).find('a').attr('href') // Payload passed to loadLinks
        })).get();

        return {
            name: title,
            posterUrl: poster,
            plot: synopsis,
            type: episodes.length > 0 ? "TvSeries" : "Movie",
            episodes: episodes
        };
    }

    /**
     * 4. Stream Links & Subtitle Extraction
     * @param {string} data - The episode payload/URL from load()
     * @param {Function} callback - Stream callback ({ name, url, quality, referer? })
     * @param {Function} subtitleCallback - Subtitle callback ({ lang, url })
     */
    async loadLinks(data, callback, subtitleCallback) {
        const res = await fetch(data);
        const $ = cheerio.load(await res.text());

        // Extract video sources
        $('video source, iframe').each((_, el) => {
            const streamUrl = $(el).attr('src');
            if (streamUrl) {
                callback({
                    name: "Direct Stream",
                    url: streamUrl,
                    quality: "1080",
                    referer: data
                });
            }
        });
    }
}
